import { graffiti, IdentityEntry, PeerEntry, openPackFile } from './graffiti-api.js';
import { onSectionShow, onWsEvent, onWsOpen, showSection } from './app.js';

const form = document.getElementById('message-form') as HTMLFormElement | null;
const fromField = document.getElementById('from-field') as HTMLSelectElement | null;
const toField = document.getElementById('to-field') as HTMLSelectElement | null;
const messageText = document.getElementById('message-text') as HTMLTextAreaElement | null;
const sendFileButton = document.getElementById('send-file') as HTMLButtonElement | null;
const fileInput = document.getElementById('file-input') as HTMLInputElement | null;
const messagesSection = document.getElementById('section-messages') as HTMLElement | null;
const messagesContainer = document.getElementById('messages') as HTMLElement | null;
const msgContextMenu = document.getElementById('msg-context-menu') as HTMLElement | null;
const statusEl = document.getElementById('send-status') as HTMLElement | null;

let isSending = false;
let isRefreshing = false;

/** Tracks keys already rendered so WS-triggered refreshes don't duplicate rows. */
const currentMessages = new Set<string>();

// ── Name / avatar lookup map ──────────────────────────────────────────────────
/** Maps a display-name to its full key string for all known identities + peers. */
const nameToKey = new Map<string, string>();
let knownIdentities: IdentityEntry[] = [];
let knownPeers: PeerEntry[] = [];

function isSavedIdentity(key: string, identities: IdentityEntry[]): boolean {
   return identities.some(id => id.key === key && id.persistent === true);
}

function isSavedRecipient(key: string, identities: IdentityEntry[], peers: PeerEntry[]): boolean {
   if (peers.some(peer => peer.key === key)) {
      return true;
   }
   return identities.some(id => id.peerKey === key && id.persistent === true);
}

async function saveOrClearRememberedFields(): Promise<void> {
   const currentFrom = fromField?.value ?? '';
   if (currentFrom && isSavedIdentity(currentFrom, knownIdentities)) {
      await graffiti.setStore('graffiti:last-from-key', currentFrom);
   } else {
      await graffiti.setStore('graffiti:last-from-key', '');
   }

   const currentTo = toField?.value ?? '';
   if (currentTo && isSavedRecipient(currentTo, knownIdentities, knownPeers)) {
      await graffiti.setStore('graffiti:last-to-key', currentTo);
   } else {
      await graffiti.setStore('graffiti:last-to-key', '');
   }
}

async function refreshNameMaps(): Promise<void> {
   try {
      const [{ identities }, { peers }] = await Promise.all([
         graffiti.listIdentities(),
         graffiti.listPeers(),
      ]);
      knownIdentities = identities;
      knownPeers = peers;
      nameToKey.clear();
      for (const id of identities) {
         nameToKey.set(id.name, id.key);
      }
      for (const peer of peers) {
         nameToKey.set(peer.name, peer.key);
      }
   } catch (e) {
      console.warn('Failed to refresh name maps:', e);
   }
}

let refreshTimeout: number | null = null;
function queueRefreshMessages(): void {
   if (refreshTimeout !== null) return;
   refreshTimeout = window.setTimeout(async () => {
      refreshTimeout = null;
      await refreshMessages();
   }, 50);
}

// ── Virtual List Controller State ─────────────────────────────────────────────
const DEFAULT_ITEM_HEIGHT = 76;
const ITEM_GAP = 8; // 0.5rem flex gap in #messages
const BUFFER_ITEMS = 8;

const itemHeights = new Map<string, number>();
const textContentCache = new Map<string, string>();
let allFilteredMessages: MessageData[] = [];
let vlistTopSpacer: HTMLDivElement | null = null;
let vlistBottomSpacer: HTMLDivElement | null = null;
let isVListRenderScheduled = false;

const itemResizeObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver((entries) => {
   let heightChanged = false;
   for (const entry of entries) {
      const el = entry.target as HTMLElement;
      const key = el.dataset.msgKey;
      if (key) {
         const newH = Math.round(el.offsetHeight);
         const oldH = itemHeights.get(key);
         if (newH > 0 && (oldH === undefined || Math.abs(newH - oldH) > 2)) {
            itemHeights.set(key, newH);
            heightChanged = true;
         }
      }
   }
   if (heightChanged) {
      scheduleVListRender();
   }
}) : null;

function scheduleVListRender(): void {
   if (isVListRenderScheduled) return;
   isVListRenderScheduled = true;
   requestAnimationFrame(() => {
      isVListRenderScheduled = false;
      renderVirtualList();
   });
}

function ensureSpacers(container: HTMLElement): { topSpacer: HTMLDivElement; bottomSpacer: HTMLDivElement } {
   if (!vlistTopSpacer || !vlistTopSpacer.parentElement) {
      vlistTopSpacer = document.createElement('div');
      vlistTopSpacer.className = 'vlist-spacer-top';
   }
   if (!vlistBottomSpacer || !vlistBottomSpacer.parentElement) {
      vlistBottomSpacer = document.createElement('div');
      vlistBottomSpacer.className = 'vlist-spacer-bottom';
   }

   if (container.firstElementChild !== vlistTopSpacer) {
      container.insertBefore(vlistTopSpacer, container.firstElementChild);
   }
   if (container.lastElementChild !== vlistBottomSpacer) {
      container.appendChild(vlistBottomSpacer);
   }
   return { topSpacer: vlistTopSpacer, bottomSpacer: vlistBottomSpacer };
}

function renderVirtualList(): void {
   const container = document.getElementById('messages');
   if (!container) return;

   const { topSpacer, bottomSpacer } = ensureSpacers(container);

   if (allFilteredMessages.length === 0) {
      topSpacer.style.height = '0px';
      topSpacer.style.display = 'none';
      bottomSpacer.style.height = '0px';
      bottomSpacer.style.display = 'none';
      const children = Array.from(container.children) as HTMLElement[];
      for (const child of children) {
         if (child !== topSpacer && child !== bottomSpacer) {
            itemResizeObserver?.unobserve(child);
            child.remove();
         }
      }
      currentMessages.clear();
      return;
   }

   const rect = container.getBoundingClientRect();
   const viewportTop = Math.max(0, -rect.top);
   const viewportBottom = viewportTop + window.innerHeight;

   let currentTop = 0;
   let rawStartIndex = 0;
   let rawEndIndex = allFilteredMessages.length - 1;
   let foundStart = false;

   for (let i = 0; i < allFilteredMessages.length; i++) {
      const msg = allFilteredMessages[i];
      const h = itemHeights.get(msg.key) ?? DEFAULT_ITEM_HEIGHT;
      const itemBottom = currentTop + h;

      if (!foundStart && itemBottom >= viewportTop) {
         rawStartIndex = i;
         foundStart = true;
      }
      if (currentTop <= viewportBottom) {
         rawEndIndex = i;
      }
      currentTop += h + ITEM_GAP;
   }

   const startIndex = Math.max(0, rawStartIndex - BUFFER_ITEMS);
   const endIndex = Math.min(allFilteredMessages.length - 1, rawEndIndex + BUFFER_ITEMS);

   let topSpacerHeight = 0;
   if (startIndex > 0) {
      for (let i = 0; i < startIndex; i++) {
         const msg = allFilteredMessages[i];
         topSpacerHeight += itemHeights.get(msg.key) ?? DEFAULT_ITEM_HEIGHT;
      }
      topSpacerHeight += (startIndex - 1) * ITEM_GAP;
   }

   let bottomSpacerHeight = 0;
   if (endIndex < allFilteredMessages.length - 1) {
      const unrenderedBottomCount = (allFilteredMessages.length - 1) - endIndex;
      for (let i = endIndex + 1; i < allFilteredMessages.length; i++) {
         const msg = allFilteredMessages[i];
         bottomSpacerHeight += itemHeights.get(msg.key) ?? DEFAULT_ITEM_HEIGHT;
      }
      bottomSpacerHeight += (unrenderedBottomCount - 1) * ITEM_GAP;
   }

   topSpacer.style.height = `${topSpacerHeight}px`;
   topSpacer.style.display = topSpacerHeight > 0 ? '' : 'none';
   bottomSpacer.style.height = `${bottomSpacerHeight}px`;
   bottomSpacer.style.display = bottomSpacerHeight > 0 ? '' : 'none';

   const visibleSlice = allFilteredMessages.slice(startIndex, endIndex + 1);
   const visibleElements: HTMLElement[] = [];

   for (const msg of visibleSlice) {
      let el = container.querySelector(`[data-msg-key="${CSS.escape(msg.key)}"]`) as HTMLElement | null;
      if (el) {
         fillHeader(el, msg);
      } else {
         el = createMessageElement(msg);
         if (el) {
            itemResizeObserver?.observe(el);
         }
      }
      if (el) {
         visibleElements.push(el);
      }
   }

   const keepSet = new Set<HTMLElement>([topSpacer, bottomSpacer, ...visibleElements]);
   const children = Array.from(container.children) as HTMLElement[];
   for (const child of children) {
      if (!keepSet.has(child)) {
         itemResizeObserver?.unobserve(child);
         child.remove();
         const key = child.dataset.msgKey;
         if (key) currentMessages.delete(key);
      }
   }

   let refNode: Node = bottomSpacer;
   for (let i = visibleElements.length - 1; i >= 0; i--) {
      const el = visibleElements[i];
      if (el.nextElementSibling !== refNode) {
         container.insertBefore(el, refNode);
      }
      refNode = el;
   }

   currentMessages.clear();
   for (const msg of visibleSlice) {
      currentMessages.add(msg.key);
   }
}

window.addEventListener('scroll', scheduleVListRender, { passive: true });
window.addEventListener('resize', scheduleVListRender, { passive: true });

let shouldScrollToBottomOnLoad = true;
let shouldScrollToBottomOnSend = false;

function isNearBottom(threshold = 150): boolean {
   const scrollBottom = window.scrollY + window.innerHeight;
   const docHeight = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight);
   return docHeight - scrollBottom <= threshold;
}

export function scrollToBottom(): void {
   const section = document.getElementById('section-messages');
   if (!section || !section.classList.contains('is-active')) return;

   const doScroll = () => {
      window.scrollTo(0, Math.max(document.documentElement.scrollHeight, document.body.scrollHeight));
   };
   doScroll();
   requestAnimationFrame(() => {
      doScroll();
      requestAnimationFrame(doScroll);
   });
}

async function refreshMessages(): Promise<void> {
   if (isRefreshing) return;
   isRefreshing = true;
   try {
      await refreshNameMaps();
      const { messages } = await graffiti.listMessages();
      const container = document.getElementById('messages');
      if (!container) return;

      const wasAtBottom = isNearBottom();
      allFilteredMessages = messages;
      renderVirtualList();
      if (shouldScrollToBottomOnLoad || shouldScrollToBottomOnSend || wasAtBottom) {
         shouldScrollToBottomOnLoad = false;
         shouldScrollToBottomOnSend = false;
         scrollToBottom();
      }
   } finally {
      isRefreshing = false;
   }
}

async function reloadMessages(): Promise<void> {
   await refreshMessages();
}

// ── Utilities ─────────────────────────────────────────────────────────────────
function autoResizeTextarea(textarea: HTMLTextAreaElement | null): void {
   if (!textarea) return;
   textarea.style.height = 'auto';
   textarea.style.height = `${textarea.scrollHeight}px`;
}

function setStatus(text: string): void {
   if (statusEl) statusEl.textContent = text;
}

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function formatTime(created: number | string | null | undefined): string {
   if (created == null) return '';
   const d = new Date(Number(created));
   if (isNaN(d.getTime())) return String(created);
   const month = MONTH_NAMES[d.getMonth()];
   const day = String(d.getDate()).padStart(2, '0');
   const hours = String(d.getHours()).padStart(2, '0');
   const minutes = String(d.getMinutes()).padStart(2, '0');
   return `${month} ${day} ${hours}:${minutes}`;
}

function formatSize(bytes: number | null | undefined): string {
   if (bytes == null) return '';
   if (bytes < 1024) return `${bytes} B`;
   if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
   return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ── Media type classification ──────────────────────────────────────────────────
const imageExtensions = new Set(['png', 'jpg', 'jpeg', 'avif', 'gif', 'bmp', 'webp', 'svg']);
const textExtensions = new Set(['txt', 'text', 'md', 'markdown', 'log', 'json', 'xml', 'csv']);
const audioExtensions = new Set(['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a']);
const videoExtensions = new Set(['mp4', 'webm', 'mkv', 'avi', 'mov']);
const htmlExtensions = new Set(['html', 'htm']);

const isImage = (t: string) => imageExtensions.has(t);
const isText = (t: string) => textExtensions.has(t);
const isAudio = (t: string) => audioExtensions.has(t);
const isVideo = (t: string) => videoExtensions.has(t);
const isHtml = (t: string) => htmlExtensions.has(t);

function pickTemplateId(type: string): string {
   if (isText(type)) return 'tpl-text-message';
   if (isImage(type)) return 'tpl-image-message';
   if (isAudio(type)) return 'tpl-audio-message';
   if (isVideo(type)) return 'tpl-video-message';
   return 'tpl-binary-message';
}

// ── Template rendering ────────────────────────────────────────────────────────
interface MessageData {
   key: string;
   author?: string;
   authorKey?: string;
   recipient?: string;
   recipientKey?: string;
   name?: string;
   size?: number;
   type: string;
   created?: number | string;
}

function fillHeader(item: HTMLElement, msg: MessageData): void {
   const authorAvatar = item.querySelector<HTMLImageElement>('.msg-author-avatar');
   const authorName = item.querySelector<HTMLElement>('.msg-author-name');
   const recipientAvatar = item.querySelector<HTMLImageElement>('.msg-recipient-avatar');
   const recipientName = item.querySelector<HTMLElement>('.msg-recipient-name');
   const timeEl = item.querySelector<HTMLTimeElement>('.msg-time');

   const authorLabel = msg.author || 'Unknown';
   const recipientLabel = msg.recipient || 'Unknown';

   if (authorName) authorName.textContent = authorLabel;
   if (recipientName) recipientName.textContent = recipientLabel;

   const authorKey = msg.authorKey || nameToKey.get(authorLabel);
   const recipientKey = msg.recipientKey || nameToKey.get(recipientLabel);

   if (authorAvatar) {
      if (authorKey) {
         authorAvatar.src = graffiti.avatarUrl(authorKey);
         authorAvatar.alt = authorLabel;
      } else {
         authorAvatar.hidden = true;
      }
   }
   if (recipientAvatar) {
      if (recipientKey) {
         recipientAvatar.src = graffiti.avatarUrl(recipientKey);
         recipientAvatar.alt = recipientLabel;
      } else {
         recipientAvatar.hidden = true;
      }
   }

   if (timeEl) {
      timeEl.textContent = formatTime(msg.created);
      const iso = new Date(Number(msg.created)).toISOString();
      if (iso !== 'Invalid Date') {
         timeEl.dateTime = iso;
         timeEl.title = new Date(Number(msg.created)).toLocaleString();
      }
   }
}

// ── Message context menu & Quote ──────────────────────────────────────────────
let activeContextMsg: MessageData | null = null;

function openContextMenu(x: number, y: number, msg: MessageData): void {
   if (!msgContextMenu) return;
   activeContextMsg = msg;

   const copyBtn = msgContextMenu.querySelector<HTMLButtonElement>('[data-action="copy"]');
   if (copyBtn) {
      copyBtn.hidden = !isText(msg.type);
   }

   msgContextMenu.hidden = false;

   // Collision-aware viewport positioning
   const padding = 8;
   const menuWidth = msgContextMenu.offsetWidth || 140;
   const menuHeight = msgContextMenu.offsetHeight || 120;

   let left = x;
   let top = y;

   if (left + menuWidth > window.innerWidth - padding) {
      left = Math.max(padding, window.innerWidth - menuWidth - padding);
   }
   if (top + menuHeight > window.innerHeight - padding) {
      top = Math.max(padding, window.innerHeight - menuHeight - padding);
   }

   msgContextMenu.style.left = `${left}px`;
   msgContextMenu.style.top = `${top}px`;
}

function closeContextMenu(): void {
   if (msgContextMenu && !msgContextMenu.hidden) {
      msgContextMenu.hidden = true;
      activeContextMsg = null;
   }
}

async function handleCopy(msg: MessageData): Promise<void> {
   let text = '';
   const cached = textContentCache.get(msg.key);
   if (cached !== undefined) {
      text = cached;
   } else {
      try {
         const url = graffiti.contentUrl(msg.key);
         const res = await fetch(url);
         text = await res.text();
         textContentCache.set(msg.key, text);
      } catch (err: any) {
         setStatus(`Failed to load text for copying: ${err?.message || err}`);
         return;
      }
   }

   try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
         await navigator.clipboard.writeText(text);
      } else {
         const ta = document.createElement('textarea');
         ta.value = text;
         ta.style.position = 'fixed';
         ta.style.opacity = '0';
         document.body.appendChild(ta);
         ta.select();
         document.execCommand('copy');
         document.body.removeChild(ta);
      }
      setStatus('Message copied to clipboard.');
   } catch (err: any) {
      setStatus(`Copy failed: ${err?.message || err}`);
   }
}

function handleDownload(msg: MessageData): void {
   const url = graffiti.contentUrl(msg.key);
   const filename = msg.name || (isText(msg.type) ? 'message.txt' : `content-${msg.key.slice(0, 8)}.${msg.type || 'bin'}`);

   if ((window as any).Android && (window as any).Android.download) {
      const fullUrl = new URL(url, window.location.origin).toString();
      (window as any).Android.download(fullUrl);
      return;
   }

   const a = document.createElement('a');
   a.href = url;
   a.download = filename;
   document.body.appendChild(a);
   a.click();
   document.body.removeChild(a);
}

async function handleQuote(msg: MessageData): Promise<void> {
   let contentToQuote = '';
   if (isText(msg.type)) {
      const cached = textContentCache.get(msg.key);
      if (cached !== undefined) {
         contentToQuote = cached;
      } else {
         try {
            const url = graffiti.contentUrl(msg.key);
            const res = await fetch(url);
            contentToQuote = await res.text();
            textContentCache.set(msg.key, contentToQuote);
         } catch {
            contentToQuote = '';
         }
      }
   }

   const authorName = msg.author || 'Unknown';
   let quoteBlock = '';

   if (isText(msg.type)) {
      const lines = contentToQuote ? contentToQuote.split('\n') : [''];
      const quoteLines = [
         `> **${authorName} wrote:**`,
         ...lines.map(line => `> ${line}`)
      ];
      quoteBlock = quoteLines.join('\n') + '\n\n';
   } else {
      const fileName = msg.name || `${msg.type} attachment`;
      quoteBlock = `> **${authorName} wrote:** [${fileName}]\n\n`;
   }

   // Note: 'to' destination in toField is left unchanged as whatever it was.

   if (messageText) {
      if (messageText.value && messageText.value.trim().length > 0) {
         messageText.value = messageText.value.trimEnd() + '\n\n' + quoteBlock;
      } else {
         messageText.value = quoteBlock;
      }
      messageText.focus();
      messageText.setSelectionRange(messageText.value.length, messageText.value.length);
      messageText.scrollIntoView({ behavior: 'smooth', block: 'center' });
   }
}
const handleReply = handleQuote;

function createMessageElement(msg: MessageData): HTMLElement | null {
   const url = graffiti.contentUrl(msg.key);
   const tpl = document.getElementById(pickTemplateId(msg.type)) as HTMLTemplateElement | null;
   if (!tpl) return null;

   const item = tpl.content.cloneNode(true) as DocumentFragment;
   const el = item.firstElementChild as HTMLElement;
   el.dataset.msgKey = msg.key;
   fillHeader(el, msg);

   if (isText(msg.type)) {
      const pre = el.querySelector<HTMLPreElement>('.msg-text-content');
      if (pre) {
         const renderText = (t: string) => {
            const isTruncated = (msg.size && msg.size > 1024 + 100) || t.length > (1024 + 100);
            if (isTruncated) {
               pre.innerHTML = renderMarkdown(t.slice(0, 1024) + '…');
               let viewBtn = el.querySelector<HTMLButtonElement>('.btn-view-text');
               if (!viewBtn) {
                  viewBtn = document.createElement('button');
                  viewBtn.type = 'button';
                  viewBtn.className = 'btn-view-text';
                  viewBtn.style.display = 'inline-flex';
                  viewBtn.style.alignItems = 'center';
                  viewBtn.style.gap = '0.25rem';
                  viewBtn.style.marginTop = '0.5rem';
                  viewBtn.style.fontSize = '0.85rem';
                  pre.after(viewBtn);
               }
               const sizeStr = msg.size ? ` (${formatSize(msg.size)})` : '';
               viewBtn.textContent = `▶ View Entire Content${sizeStr}`;
               viewBtn.onclick = (e: MouseEvent) => {
                  e.preventDefault();
                  e.stopPropagation();
                  openFullContentViewer(msg, t);
               };
            } else {
               pre.innerHTML = renderMarkdown(t);
               const existingBtn = el.querySelector('.btn-view-text');
               if (existingBtn) existingBtn.remove();
            }
         };

         const cached = textContentCache.get(msg.key);
         if (cached !== undefined) {
            renderText(cached);
         } else {
            fetch(url)
               .then(r => r.text())
               .then(t => {
                  textContentCache.set(msg.key, t);
                  renderText(t);
               })
               .catch((err: Error) => {
                  pre.textContent = `[Error loading content: ${err.message}]`;
               });
         }
      }
   } else if (isImage(msg.type)) {
      const imgEl = el.querySelector<HTMLImageElement>('.msg-media');
      if (imgEl) {
         imgEl.src = url;
         imgEl.alt = msg.name || 'Image';
         imgEl.classList.add('is-clickable');
         imgEl.title = 'Click to open in dedicated viewer';
         imgEl.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            openFullContentViewer(msg);
         });
      }
   } else if (isAudio(msg.type)) {
      const fileNameEl = el.querySelector<HTMLElement>('.msg-file-name');
      if (fileNameEl) fileNameEl.textContent = msg.name || '';
      const audio = el.querySelector<HTMLAudioElement>('.msg-media');
      if (audio) audio.src = url;
      let viewBtn = el.querySelector<HTMLButtonElement>('.btn-view-media');
      if (!viewBtn) {
         viewBtn = document.createElement('button');
         viewBtn.type = 'button';
         viewBtn.className = 'btn-view-media';
         viewBtn.style.display = 'inline-flex';
         viewBtn.style.alignItems = 'center';
         viewBtn.style.gap = '0.25rem';
         viewBtn.style.marginTop = '0.4rem';
         viewBtn.style.fontSize = '0.82rem';
         viewBtn.innerHTML = '<span class="material-symbols-outlined" style="font-size: 1rem;">visibility</span> Full Viewer';
         viewBtn.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openFullContentViewer(msg);
         };
         audio?.after(viewBtn);
      }
   } else if (isVideo(msg.type)) {
      const fileNameEl = el.querySelector<HTMLElement>('.msg-file-name');
      if (fileNameEl) fileNameEl.textContent = msg.name || '';
      const video = el.querySelector<HTMLVideoElement>('.msg-media');
      if (video) video.src = url;
      let viewBtn = el.querySelector<HTMLButtonElement>('.btn-view-media');
      if (!viewBtn) {
         viewBtn = document.createElement('button');
         viewBtn.type = 'button';
         viewBtn.className = 'btn-view-media';
         viewBtn.style.display = 'inline-flex';
         viewBtn.style.alignItems = 'center';
         viewBtn.style.gap = '0.25rem';
         viewBtn.style.marginTop = '0.4rem';
         viewBtn.style.fontSize = '0.82rem';
         viewBtn.innerHTML = '<span class="material-symbols-outlined" style="font-size: 1rem;">visibility</span> Full Viewer';
         viewBtn.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openFullContentViewer(msg);
         };
         video?.after(viewBtn);
      }
   } else {
      const fileNameEl = el.querySelector<HTMLElement>('.msg-file-name');
      if (fileNameEl) fileNameEl.textContent = msg.name || 'File';
      const link = el.querySelector<HTMLAnchorElement>('.msg-download-link');
      if (link) {
         link.href = url;
         link.download = msg.name || 'download';
         const sizeStr = msg.size ? ` (${formatSize(msg.size)})` : '';
         link.textContent = `⬇ Download ${msg.name || 'file'}${sizeStr}`;
      }

      const isPackFile = msg.name && (msg.name.toLowerCase().endsWith('.pack') || msg.name.toLowerCase().endsWith('.epack'));
      const isHtmlFile = isHtml(msg.type) || (msg.name && (msg.name.toLowerCase().endsWith('.html') || msg.name.toLowerCase().endsWith('.htm')));

      let viewBtn = el.querySelector<HTMLButtonElement>('.btn-view-media');
      if (!viewBtn && (isPackFile || isHtmlFile)) {
         viewBtn = document.createElement('button');
         viewBtn.type = 'button';
         viewBtn.className = 'btn-view-media';
         viewBtn.style.marginLeft = '8px';
         viewBtn.style.display = 'inline-flex';
         viewBtn.style.alignItems = 'center';
         viewBtn.style.gap = '0.25rem';
         viewBtn.innerHTML = '<span class="material-symbols-outlined" style="font-size: 1rem;">visibility</span> View';
         viewBtn.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openFullContentViewer(msg);
         };
         link?.parentElement?.appendChild(viewBtn);
      }
   }

   return el;
}

function displayMessage(msg: MessageData): void {
   allFilteredMessages.push(msg);
   renderVirtualList();
}

async function populateSelects(): Promise<void> {
   const [{ identities }, { peers }, node] = await Promise.all([
      graffiti.listIdentities(),
      graffiti.listPeers(),
      graffiti.nodeInfo(),
   ]);
   knownIdentities = identities;
   knownPeers = peers;

   nameToKey.clear();
   for (const id of identities) {
      nameToKey.set(id.name, id.key);
   }
   for (const peer of peers) {
      nameToKey.set(peer.name, peer.key);
   }

   const prevFrom = fromField?.value ?? '';
   const prevTo = toField?.value ?? '';

   const [savedFromKey, savedToKey] = await Promise.all([
      graffiti.getStore('graffiti:last-from-key'),
      graffiti.getStore('graffiti:last-to-key'),
   ]);

   // Populate From: all available identities
   const fromEmptyMsg = document.getElementById('from-empty-message');
   if (fromField) {
      fromField.replaceChildren();
      if (identities.length === 0) {
         fromField.style.display = 'none';
         if (fromEmptyMsg) fromEmptyMsg.style.display = '';
      } else {
         fromField.style.display = '';
         if (fromEmptyMsg) fromEmptyMsg.style.display = 'none';
         for (const id of identities) {
            const opt = document.createElement('option');
            opt.value = id.key;
            opt.textContent = id.name;
            fromField.append(opt);
         }
         if (prevFrom && identities.some(id => id.key === prevFrom)) {
            fromField.value = prevFrom;
         } else if (savedFromKey && isSavedIdentity(savedFromKey, identities)) {
            fromField.value = savedFromKey;
         } else if (identities.some(id => id.key === node.peerKey)) {
            fromField.value = node.peerKey;
         }
      }
   }

   // Populate To: all available peers + identities
   const toEmptyMsg = document.getElementById('to-empty-message');
   if (toField) {
      toField.replaceChildren();
      const hasOptions = peers.length > 0 || identities.length > 0;
      if (!hasOptions) {
         toField.style.display = 'none';
         if (toEmptyMsg) {
            toEmptyMsg.textContent = 'No peers or identities available';
            toEmptyMsg.style.display = '';
         }
      } else {
         toField.style.display = '';
         if (toEmptyMsg) toEmptyMsg.style.display = 'none';
         for (const id of identities) {
            const opt = document.createElement('option');
            opt.value = id.peerKey;   // PeerKey, not IdentityKey
            opt.textContent = id.name;
            toField.append(opt);
         }
         for (const peer of peers) {
            const opt = document.createElement('option');
            opt.value = peer.key;
            opt.textContent = peer.name;
            toField.append(opt);
         }
         if (prevTo) {
            const exists = Array.from(toField.options).some(opt => opt.value === prevTo);
            if (exists) {
               toField.value = prevTo;
            }
         } else if (savedToKey && isSavedRecipient(savedToKey, identities, peers)) {
            const exists = Array.from(toField.options).some(opt => opt.value === savedToKey);
            if (exists) {
               toField.value = savedToKey;
            }
         }
      }
   }
   updateSameAuthorRecipientWarning();
   void saveOrClearRememberedFields();
}

function getEnvelope(): { identityKey: string; peerKey: string } {
   return {
      identityKey: fromField?.value ?? '',
      peerKey: toField?.value ?? '',
   };
}

type Payload =
   | { type: 'text'; text: string; identityKey: string; peerKey: string }
   | { type: 'file'; fileName: string; file: File; identityKey: string; peerKey: string; source?: string };

async function sendPayload(payload: Payload): Promise<void> {
   if (isSending) {
      setStatus('Send in progress. Only one item can be sent at a time.');
      return;
   }
   isSending = true;
   setStatus(`Sending ${payload.type}`);
   try {
      const { identityKey, peerKey } = payload;
      if (!identityKey || !peerKey) throw new Error('Select a sender and recipient first.');
      if (payload.type === 'text') {
         await graffiti.sendText(identityKey, peerKey, payload.text);
      } else {
         await graffiti.sendFile(identityKey, peerKey, payload.file);
      }
      setStatus(`${payload.type} sent.`);
      shouldScrollToBottomOnSend = true;
      await refreshMessages();
      scrollToBottom();
   } catch (err) {
      setStatus(`Failed: ${(err as Error).message}`);
   } finally {
      isSending = false;
   }
}

interface DroppedContent {
   kind: 'file' | 'text' | 'html';
   value: File | string;
}

function firstDroppedContent(dataTransfer: DataTransfer | null): DroppedContent | null {
   if (!dataTransfer) return null;
   if (dataTransfer.files?.length > 0) return { kind: 'file', value: dataTransfer.files[0] };
   const plain = dataTransfer.getData('text/plain');
   if (plain) return { kind: 'text', value: plain };
   const html = dataTransfer.getData('text/html');
   if (html) return { kind: 'html', value: html };
   return null;
}

// ── Form events ───────────────────────────────────────────────────────────────
form?.addEventListener('submit', async (event: SubmitEvent) => {
   event.preventDefault();
   const text = (messageText?.value ?? '').trim();
   if (!text) {
      setStatus('Type a message before sending.');
      return;
   }
   await sendPayload({ type: 'text', text, ...getEnvelope() });
   if (!isSending && messageText) {
      messageText.value = '';
      autoResizeTextarea(messageText);
      scrollToBottom();
   }
});

messageText?.addEventListener('input', () => autoResizeTextarea(messageText));
messageText?.addEventListener('keydown', (event: KeyboardEvent) => {
   if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      form?.requestSubmit();
   }
});

sendFileButton?.addEventListener('click', () => fileInput?.click());

function updateSameAuthorRecipientWarning(): void {
   const warningEl = document.getElementById('same-author-recipient-warning');
   if (!warningEl) return;
   const fromKey = fromField?.value;
   const toKey = toField?.value;
   if (!fromKey || !toKey) {
      warningEl.style.display = 'none';
      return;
   }
   const selectedIdentity = knownIdentities.find(id => id.key === fromKey);
   if (selectedIdentity && selectedIdentity.peerKey === toKey) {
      warningEl.style.display = 'block';
   } else {
      warningEl.style.display = 'none';
   }
}

fromField?.addEventListener('change', () => {
   updateSameAuthorRecipientWarning();
   void saveOrClearRememberedFields();
});
toField?.addEventListener('change', () => {
   updateSameAuthorRecipientWarning();
   void saveOrClearRememberedFields();
});



fileInput?.addEventListener('change', async () => {
   const file = fileInput?.files?.[0];
   if (!file) return;
   await sendPayload({ type: 'file', fileName: file.name, file, ...getEnvelope() });
   if (fileInput) fileInput.value = '';
   scrollToBottom();
});

// ── Drag-and-drop ─────────────────────────────────────────────────────────────
let dragDepth = 0;

messagesSection?.addEventListener('dragenter', (e: DragEvent) => {
   e.preventDefault();
   e.stopPropagation();
   if (dragDepth++ === 0) messagesSection.classList.add('is-dragover');
});

messagesSection?.addEventListener('dragover', (e: DragEvent) => {
   e.preventDefault();
   e.stopPropagation();
});

messagesSection?.addEventListener('dragleave', (e: DragEvent) => {
   e.stopPropagation();
   if (--dragDepth === 0) messagesSection.classList.remove('is-dragover');
});

messagesSection?.addEventListener('drop', async (event: DragEvent) => {
   event.preventDefault();
   event.stopPropagation();
   dragDepth = 0;
   messagesSection.classList.remove('is-dragover');
   const content = firstDroppedContent(event.dataTransfer);
   if (!content) {
      setStatus('Nothing to send from drop.');
      return;
   }
   if (content.kind === 'file') {
      const file = content.value as File;
      await sendPayload({ type: 'file', fileName: file.name, file, ...getEnvelope() });
      return;
   }
   await sendPayload({ type: 'text', text: content.value as string, ...getEnvelope() });
});

// ── Clipboard paste (files / screenshots) ────────────────────────────────────
messagesSection?.addEventListener('paste', async (event: ClipboardEvent) => {
   const files = event.clipboardData?.files;
   if (files && files.length > 0) {
      event.preventDefault();
      const file = files[0];
      await sendPayload({ type: 'file', fileName: file.name, file, ...getEnvelope() });
   }
});



// ── Bootstrap & Foreground Lifecycle ──────────────────────────────────────────
setStatus('Ready');
autoResizeTextarea(messageText);

const composerElement = document.querySelector('.composer') as HTMLElement | null;
function updateComposerHeight(): void {
   if (!composerElement) return;
   const height = composerElement.offsetHeight;
   if (height > 0) {
      document.documentElement.style.setProperty('--composer-height', `${height}px`);
   }
}

if (composerElement && typeof ResizeObserver !== 'undefined') {
   new ResizeObserver(() => {
      updateComposerHeight();
      scheduleVListRender();
   }).observe(composerElement);
   updateComposerHeight();
}

onSectionShow('section-messages', () => {
   updateComposerHeight();
   shouldScrollToBottomOnLoad = true;
   void populateSelects();
   scheduleVListRender();
   scrollToBottom();
   void queueRefreshMessages();
});

onWsOpen(() => {
   void populateSelects();
   void queueRefreshMessages();
});

async function handleForegroundRefresh(): Promise<void> {
   try {
      await graffiti.refreshMessages();
   } catch (e) {
      console.warn('Foreground refreshMessages error:', e);
   }
   await populateSelects();
   await refreshMessages();
}

document.addEventListener('visibilitychange', () => {
   if (document.visibilityState === 'visible') {
      void handleForegroundRefresh();
   }
});

window.addEventListener('focus', () => {
   void handleForegroundRefresh();
});

window.addEventListener('pageshow', () => {
   void handleForegroundRefresh();
});

queueRefreshMessages();

// ── Notifications ─────────────────────────────────────────────────────────────
let lastNotificationTime = 0;

function getMessageTypeText(type: string): string {
   if (isText(type)) return 'New text message';
   if (isImage(type)) return 'New image message';
   if (isAudio(type)) return 'New audio message';
   if (isVideo(type)) return 'New video message';
   return 'New file message';
}

function notifyNewMessage(msg: MessageData): void {
   if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      if (document.hidden || !document.hasFocus()) {
         const now = Date.now();
         if (now - lastNotificationTime < 2000) return; // rate limit notifications to 2s
         lastNotificationTime = now;

         const author = msg.author || 'Unknown';
         const textPreview = getMessageTypeText(msg.type);
         new Notification(`Graffiti: Message from ${author}`, {
            body: textPreview,
            icon: 'graffiti.png'
         });
      }
   }
}

// Request notification permission on first user click
if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
   const requestPermission = () => {
      Notification.requestPermission().catch(err => console.warn('Notification permission request failed:', err));
   };
   document.addEventListener('click', requestPermission, { once: true });
}

// ── WebSocket hooks ───────────────────────────────────────────────────────────
onWsEvent('messages_update', async (msg: Record<string, unknown>) => {
   if (msg.action === 'remove') {
      const removedKey = msg.key as string;
      currentMessages.delete(removedKey);
      itemHeights.delete(removedKey);
      allFilteredMessages = allFilteredMessages.filter(m => m.key !== removedKey);
      renderVirtualList();
   } else if (msg.action === 'add') {
      const m = msg.msg as MessageData | undefined;
      if (m && !currentMessages.has(m.key)) {
         notifyNewMessage(m);
      }
      queueRefreshMessages();
   } else {
      queueRefreshMessages();
   }
});
onWsEvent('identities_update', () => {
   void populateSelects();
   void queueRefreshMessages();
});
onWsEvent('messages_reload', queueRefreshMessages);
onWsEvent('peers_update', () => {
   void populateSelects();
   void queueRefreshMessages();
});

// ── Unified Media Content Viewer & Zoom Controller ────────────────────────────

let currentViewerMsg: MessageData | null = null;
let currentRawText: string = '';
let isRawTextView: boolean = false;
let currentTextFontSizePercent: number = 100;

// Image zoom & pan state
let imgScale = 1.0;
let imgTranslateX = 0;
let imgTranslateY = 0;
let isImgDragging = false;
let imgDragStartX = 0;
let imgDragStartY = 0;
let imgInitialPinchDist = 0;
let imgInitialScale = 1.0;
let imgLastTapTime = 0;

function applyImageTransform(animate = false): void {
   const img = document.getElementById('view-content-image') as HTMLImageElement | null;
   const badge = document.getElementById('img-zoom-level');
   if (!img) return;
   img.style.transition = animate ? 'transform 0.18s ease-out' : 'none';
   img.style.transform = `translate(${imgTranslateX}px, ${imgTranslateY}px) scale(${imgScale})`;
   if (badge) {
      badge.textContent = `${Math.round(imgScale * 100)}%`;
   }
}

function resetImageZoom(): void {
   imgScale = 1.0;
   imgTranslateX = 0;
   imgTranslateY = 0;
   isImgDragging = false;
   applyImageTransform(true);
}

function zoomImageIn(): void {
   imgScale = Math.min(10.0, +(imgScale + 0.25).toFixed(2));
   applyImageTransform(true);
}

function zoomImageOut(): void {
   imgScale = Math.max(0.25, +(imgScale - 0.25).toFixed(2));
   if (imgScale <= 1.0) {
      imgTranslateX = 0;
      imgTranslateY = 0;
   }
   applyImageTransform(true);
}

export function cleanupViewerMedia(): void {
   const video = document.getElementById('view-content-video') as HTMLVideoElement | null;
   if (video) {
      video.pause();
      video.removeAttribute('src');
      video.load();
   }
   const audio = document.getElementById('view-content-audio') as HTMLAudioElement | null;
   if (audio) {
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
   }
   const iframe = document.getElementById('view-content-iframe') as HTMLIFrameElement | null;
   if (iframe) {
      iframe.src = 'about:blank';
   }
   resetImageZoom();
}

function initImageZoomController(): void {
   const viewport = document.getElementById('view-content-image-viewport');
   const zoomInBtn = document.getElementById('btn-img-zoom-in');
   const zoomOutBtn = document.getElementById('btn-img-zoom-out');
   const zoomResetBtn = document.getElementById('btn-img-zoom-reset');

   zoomInBtn?.addEventListener('click', (e) => {
      e.preventDefault();
      zoomImageIn();
   });
   zoomOutBtn?.addEventListener('click', (e) => {
      e.preventDefault();
      zoomImageOut();
   });
   zoomResetBtn?.addEventListener('click', (e) => {
      e.preventDefault();
      resetImageZoom();
   });

   if (!viewport) return;

   // Touch gestures: pinch-to-zoom, pan, double-tap
   viewport.addEventListener('touchstart', (e: TouchEvent) => {
      if (e.touches.length === 1) {
         const touch = e.touches[0];
         const now = Date.now();
         if (now - imgLastTapTime < 320) {
            // Double-tap: toggle 1.0x <-> 2.5x
            if (imgScale > 1.2) {
               resetImageZoom();
            } else {
               imgScale = 2.5;
               const rect = viewport.getBoundingClientRect();
               const centerX = rect.left + rect.width / 2;
               const centerY = rect.top + rect.height / 2;
               imgTranslateX = (centerX - touch.clientX) * 0.75;
               imgTranslateY = (centerY - touch.clientY) * 0.75;
               applyImageTransform(true);
            }
            imgLastTapTime = 0;
            return;
         }
         imgLastTapTime = now;

         if (imgScale > 1.0) {
            isImgDragging = true;
            imgDragStartX = touch.clientX - imgTranslateX;
            imgDragStartY = touch.clientY - imgTranslateY;
         }
      } else if (e.touches.length === 2) {
         isImgDragging = false;
         const t1 = e.touches[0];
         const t2 = e.touches[1];
         imgInitialPinchDist = Math.hypot(t2.clientX - t1.clientX, t2.clientY - t1.clientY);
         imgInitialScale = imgScale;
      }
   }, { passive: false });

   viewport.addEventListener('touchmove', (e: TouchEvent) => {
      if (e.touches.length === 2 && imgInitialPinchDist > 0) {
         e.preventDefault();
         const t1 = e.touches[0];
         const t2 = e.touches[1];
         const currentDist = Math.hypot(t2.clientX - t1.clientX, t2.clientY - t1.clientY);
         const newScale = Math.min(10.0, Math.max(0.5, +(imgInitialScale * (currentDist / imgInitialPinchDist)).toFixed(2)));
         imgScale = newScale;
         applyImageTransform(false);
      } else if (e.touches.length === 1 && isImgDragging && imgScale > 1.0) {
         e.preventDefault();
         const touch = e.touches[0];
         imgTranslateX = touch.clientX - imgDragStartX;
         imgTranslateY = touch.clientY - imgDragStartY;
         applyImageTransform(false);
      }
   }, { passive: false });

   viewport.addEventListener('touchend', (e: TouchEvent) => {
      if (e.touches.length === 0) {
         isImgDragging = false;
         imgInitialPinchDist = 0;
         if (imgScale <= 1.0) {
            imgTranslateX = 0;
            imgTranslateY = 0;
            applyImageTransform(true);
         }
      } else if (e.touches.length === 1 && imgScale > 1.0) {
         const touch = e.touches[0];
         isImgDragging = true;
         imgDragStartX = touch.clientX - imgTranslateX;
         imgDragStartY = touch.clientY - imgTranslateY;
      }
   });

   // Mouse events: drag to pan when zoomed, mouse wheel to zoom
   viewport.addEventListener('mousedown', (e: MouseEvent) => {
      if (e.button === 0 && imgScale > 1.0) {
         isImgDragging = true;
         imgDragStartX = e.clientX - imgTranslateX;
         imgDragStartY = e.clientY - imgTranslateY;
         viewport.classList.add('is-dragging');
      }
   });

   window.addEventListener('mousemove', (e: MouseEvent) => {
      if (isImgDragging) {
         imgTranslateX = e.clientX - imgDragStartX;
         imgTranslateY = e.clientY - imgDragStartY;
         applyImageTransform(false);
      }
   });

   window.addEventListener('mouseup', () => {
      if (isImgDragging) {
         isImgDragging = false;
         viewport.classList.remove('is-dragging');
      }
   });

   viewport.addEventListener('wheel', (e: WheelEvent) => {
      e.preventDefault();
      const delta = e.deltaY < 0 ? 0.25 : -0.25;
      imgScale = Math.min(10.0, Math.max(0.25, +(imgScale + delta).toFixed(2)));
      if (imgScale <= 1.0) {
         imgTranslateX = 0;
         imgTranslateY = 0;
      }
      applyImageTransform(false);
   }, { passive: false });
}

function initTextViewerControls(): void {
   const decBtn = document.getElementById('btn-text-font-dec');
   const incBtn = document.getElementById('btn-text-font-inc');
   const rawBtn = document.getElementById('btn-text-toggle-raw');
   const textPre = document.getElementById('view-content-text');

   decBtn?.addEventListener('click', () => {
      currentTextFontSizePercent = Math.max(50, currentTextFontSizePercent - 10);
      if (textPre) textPre.style.fontSize = `${currentTextFontSizePercent}%`;
   });

   incBtn?.addEventListener('click', () => {
      currentTextFontSizePercent = Math.min(250, currentTextFontSizePercent + 10);
      if (textPre) textPre.style.fontSize = `${currentTextFontSizePercent}%`;
   });

   rawBtn?.addEventListener('click', () => {
      isRawTextView = !isRawTextView;
      rawBtn.classList.toggle('is-active', isRawTextView);
      if (textPre) {
         if (isRawTextView) {
            textPre.textContent = currentRawText;
         } else {
            textPre.innerHTML = renderMarkdown(currentRawText);
         }
      }
   });
}

export function openFullContentViewer(msg: MessageData, fullText?: string): void {
   currentViewerMsg = msg;
   const url = graffiti.contentUrl(msg.key);

   const filenameEl = document.getElementById('view-content-filename');
   const metaEl = document.getElementById('view-content-meta');

   const authorLabel = msg.author || 'Unknown';
   const recipientLabel = msg.recipient || 'Unknown';
   const sizeStr = msg.size ? ` • ${formatSize(msg.size)}` : '';
   const timeStr = msg.created ? ` • ${formatTime(msg.created)}` : '';

   if (filenameEl) {
      filenameEl.textContent = msg.name || (isText(msg.type) ? 'Text Message' : `${msg.type.toUpperCase()} Media`);
   }
   if (metaEl) {
      metaEl.textContent = `From ${authorLabel} to ${recipientLabel}${sizeStr}${timeStr}`;
   }

   // Cleanup any currently playing media
   cleanupViewerMedia();

   // Containers
   const imgContainer = document.getElementById('view-content-image-container');
   const textContainer = document.getElementById('view-content-text-container');
   const videoContainer = document.getElementById('view-content-video-container');
   const audioContainer = document.getElementById('view-content-audio-container');
   const htmlContainer = document.getElementById('view-content-html-container');
   const packContainer = document.getElementById('view-content-pack-container');
   const genericContainer = document.getElementById('view-content-generic-container');

   if (imgContainer) imgContainer.style.display = 'none';
   if (textContainer) textContainer.style.display = 'none';
   if (videoContainer) videoContainer.style.display = 'none';
   if (audioContainer) audioContainer.style.display = 'none';
   if (htmlContainer) htmlContainer.style.display = 'none';
   if (packContainer) packContainer.style.display = 'none';
   if (genericContainer) genericContainer.style.display = 'none';

   const isHtmlFile = isHtml(msg.type) || (msg.name && (msg.name.toLowerCase().endsWith('.html') || msg.name.toLowerCase().endsWith('.htm')));
   const isPackFile = msg.name && (msg.name.toLowerCase().endsWith('.pack') || msg.name.toLowerCase().endsWith('.epack'));

   if (isImage(msg.type)) {
      if (imgContainer) imgContainer.style.display = '';
      const img = document.getElementById('view-content-image') as HTMLImageElement | null;
      if (img) {
         img.src = url;
         img.alt = msg.name || 'Image';
      }
      resetImageZoom();
   } else if (isVideo(msg.type)) {
      if (videoContainer) videoContainer.style.display = '';
      const video = document.getElementById('view-content-video') as HTMLVideoElement | null;
      if (video) {
         video.src = url;
         video.load();
      }
   } else if (isAudio(msg.type)) {
      if (audioContainer) audioContainer.style.display = '';
      const audio = document.getElementById('view-content-audio') as HTMLAudioElement | null;
      const audioTitle = document.getElementById('view-content-audio-title');
      if (audioTitle) audioTitle.textContent = msg.name || 'Audio Track';
      if (audio) {
         audio.src = url;
         audio.load();
      }
   } else if (isHtmlFile) {
      if (htmlContainer) htmlContainer.style.display = '';
      const iframe = document.getElementById('view-content-iframe') as HTMLIFrameElement | null;
      if (iframe) {
         iframe.src = url;
      }
   } else if (isPackFile) {
      if (packContainer) packContainer.style.display = '';
      const packTitle = document.getElementById('view-content-pack-title');
      const openBtn = document.getElementById('btn-view-pack-open') as HTMLButtonElement | null;
      if (packTitle) packTitle.textContent = msg.name || 'Web Pack Archive';
      if (openBtn) {
         openBtn.onclick = () => {
            void openPackFile({ encKey: msg.key, name: msg.name });
         };
      }
   } else if (isText(msg.type)) {
      if (textContainer) textContainer.style.display = '';
      const textPre = document.getElementById('view-content-text');
      isRawTextView = false;
      const rawBtn = document.getElementById('btn-text-toggle-raw');
      if (rawBtn) rawBtn.classList.remove('is-active');
      if (textPre) {
         textPre.style.fontSize = `${currentTextFontSizePercent}%`;
         const render = (t: string) => {
            currentRawText = t;
            textPre.innerHTML = renderMarkdown(t);
         };

         if (fullText !== undefined && fullText !== '') {
            render(fullText);
         } else {
            const cached = textContentCache.get(msg.key);
            if (cached !== undefined) {
               render(cached);
            } else {
               textPre.textContent = 'Loading content…';
               fetch(url)
                  .then(r => r.text())
                  .then(t => {
                     textContentCache.set(msg.key, t);
                     render(t);
                  })
                  .catch((err: Error) => {
                     textPre.textContent = `[Error loading content: ${err.message}]`;
                  });
            }
         }
      }
   } else {
      if (genericContainer) genericContainer.style.display = '';
      const genericName = document.getElementById('view-content-generic-name');
      const genericMeta = document.getElementById('view-content-generic-meta');
      if (genericName) genericName.textContent = msg.name || 'File Attachment';
      if (genericMeta) genericMeta.textContent = `${msg.type ? `Type: ${msg.type.toUpperCase()}` : ''}${sizeStr}`;
   }

   showSection('section-view-content');
}

function escHtml(str: string): string {
   return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
}

function inlineFormat(str: string): string {
   const urls: string[] = [];
   let formatted = str.replace(/(https?:\/\/[^\s<]+)/gi, (match) => {
      let cleanUrl = match;
      let trailingPunct = '';
      while (/[.,!?)]$/.test(cleanUrl) && !cleanUrl.endsWith('()')) {
         trailingPunct = cleanUrl.slice(-1) + trailingPunct;
         cleanUrl = cleanUrl.slice(0, -1);
      }
      const idx = urls.length;
      urls.push(cleanUrl);
      return `@@@URL_${idx}@@@${trailingPunct}`;
   });

   formatted = formatted
      .replace(/(\*\*\*|___)(.*?)\1/g, '<strong><em>$2</em></strong>')
      .replace(/(\*\*|__)(.*?)\1/g, '<strong>$2</strong>')
      .replace(/(^|\s|\()(\*|_)(.*?)\2(?=\s|\)|$|\.|,|\?|!)/g, '$1<em>$3</em>')
      .replace(/~~(.*?)~~/g, '<del>$1</del>');

   return formatted.replace(/@@@URL_(\d+)@@@/g, (_m, idxStr) => {
      const idx = Number(idxStr);
      const rawUrl = urls[idx] || '';
      const hrefUrl = rawUrl.replace(/&amp;/g, '&');
      return `<a href="${hrefUrl}" target="_blank" rel="noopener" class="msg-link">${rawUrl}</a>`;
   });
}

export function renderMarkdown(raw: string): string {
   if (!raw) return '';

   let html = escHtml(raw);

   const codeBlocks: string[] = [];
   html = html.replace(/```([\s\S]*?)```/g, (_match, p1) => {
      const index = codeBlocks.length;
      codeBlocks.push(`<pre class="msg-code-block"><code>${p1.trim()}</code></pre>`);
      return `@@@CODEBLOCK_${index}@@@`;
   });

   html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

   const lines = html.split('\n');
   const result: string[] = [];
   let inList = false;
   let listType: 'ul' | 'ol' | null = null;
   let inBlockquote = false;
   let blockquoteLines: string[] = [];

   const flushBlockquote = () => {
      if (inBlockquote) {
         result.push(`<blockquote class="msg-blockquote">${blockquoteLines.map(l => inlineFormat(l)).join('<br>')}</blockquote>`);
         inBlockquote = false;
         blockquoteLines = [];
      }
   };

   for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      if (line.includes('@@@CODEBLOCK_')) {
         if (inList) {
            result.push(listType === 'ul' ? '</ul>' : '</ol>');
            inList = false;
            listType = null;
         }
         flushBlockquote();
         result.push(line);
         continue;
      }

      const h3Match = line.match(/^###\s+(.+)$/);
      const h2Match = line.match(/^##\s+(.+)$/);
      const h1Match = line.match(/^#\s+(.+)$/);
      const ulMatch = line.match(/^[\*\-\+]\s+(.+)$/);
      const olMatch = line.match(/^\d+\.\s+(.+)$/);
      const bqMatch = line.match(/^&gt;\s?(.*)$/);

      if (bqMatch) {
         if (inList) {
            result.push(listType === 'ul' ? '</ul>' : '</ol>');
            inList = false;
            listType = null;
         }
         inBlockquote = true;
         blockquoteLines.push(bqMatch[1]);
         continue;
      } else {
         flushBlockquote();
      }

      if (ulMatch || olMatch) {
         const currentType = ulMatch ? 'ul' : 'ol';
         const itemContent = ulMatch ? ulMatch[1] : olMatch![1];

         if (!inList || listType !== currentType) {
            if (inList) {
               result.push(listType === 'ul' ? '</ul>' : '</ol>');
            }
            result.push(currentType === 'ul' ? '<ul class="msg-list">' : '<ol class="msg-list">');
            inList = true;
            listType = currentType;
         }
         result.push(`<li>${inlineFormat(itemContent)}</li>`);
         continue;
      } else if (inList) {
         result.push(listType === 'ul' ? '</ul>' : '</ol>');
         inList = false;
         listType = null;
      }

      if (h3Match) {
         result.push(`<h3 class="msg-h3">${inlineFormat(h3Match[1])}</h3>`);
      } else if (h2Match) {
         result.push(`<h2 class="msg-h2">${inlineFormat(h2Match[1])}</h2>`);
      } else if (h1Match) {
         result.push(`<h1 class="msg-h1">${inlineFormat(h1Match[1])}</h1>`);
      } else if (line.trim() === '') {
         result.push('<div class="msg-spacer"></div>');
      } else {
         result.push(`<p class="msg-para">${inlineFormat(line)}</p>`);
      }
   }

   if (inList) {
      result.push(listType === 'ul' ? '</ul>' : '</ol>');
   }
   flushBlockquote();

   let finalHtml = result.join('');
   finalHtml = finalHtml.replace(/@@@CODEBLOCK_(\d+)@@@/g, (_m, idx) => codeBlocks[Number(idx)] || '');
   return finalHtml;
}

document.addEventListener('click', (e: MouseEvent) => {
   const target = e.target as HTMLElement | null;
   if (!target) return;

   const viewBtn = target.closest<HTMLButtonElement>('.btn-view-text, .btn-view-media');
   if (viewBtn) {
      e.preventDefault();
      e.stopPropagation();
      const item = viewBtn.closest<HTMLElement>('[data-msg-key]');
      const key = item?.dataset.msgKey;
      if (key) {
         const msg = allFilteredMessages.find(m => m.key === key);
         if (msg) {
            openFullContentViewer(msg, textContentCache.get(key));
         }
      }
      return;
   }

   const downloadBtn = target.closest('#btn-download-view-content');
   if (downloadBtn) {
      e.preventDefault();
      e.stopPropagation();
      if (currentViewerMsg) {
         handleDownload(currentViewerMsg);
      }
      return;
   }

   const closeBtn = target.closest('#btn-close-view-content');
   if (closeBtn) {
      e.preventDefault();
      e.stopPropagation();
      cleanupViewerMedia();
      showSection('section-messages');
      return;
   }
});

// ── Context Menu Actions & Event Listeners ────────────────────────────────────
msgContextMenu?.addEventListener('click', async (e: MouseEvent) => {
   const target = e.target as HTMLElement | null;
   const btn = target?.closest<HTMLButtonElement>('.msg-context-item');
   if (!btn || !activeContextMsg) return;

   const action = btn.dataset.action;
   const msg = activeContextMsg;
   closeContextMenu();

   if (action === 'view') {
      openFullContentViewer(msg, textContentCache.get(msg.key));
   } else if (action === 'delete') {
      try {
         await graffiti.removeMessage(msg.key);
         currentMessages.delete(msg.key);
         itemHeights.delete(msg.key);
         textContentCache.delete(msg.key);
         allFilteredMessages = allFilteredMessages.filter(m => m.key !== msg.key);
         renderVirtualList();
      } catch (err: any) {
         setStatus(`Delete failed: ${err?.message || err}`);
      }
   } else if (action === 'quote' || action === 'reply') {
      await handleQuote(msg);
   } else if (action === 'copy') {
      await handleCopy(msg);
   } else if (action === 'download') {
      handleDownload(msg);
   }
});

document.addEventListener('click', (e: MouseEvent) => {
   if (msgContextMenu && !msgContextMenu.hidden && !msgContextMenu.contains(e.target as Node)) {
      closeContextMenu();
   }
});

document.addEventListener('keydown', (e: KeyboardEvent) => {
   if (e.key === 'Escape') {
      closeContextMenu();
   }
});

window.addEventListener('scroll', () => {
   if (msgContextMenu && !msgContextMenu.hidden) {
      closeContextMenu();
   }
}, { passive: true });

window.addEventListener('resize', () => {
   if (msgContextMenu && !msgContextMenu.hidden) {
      closeContextMenu();
   }
}, { passive: true });

// Right-click on desktop
messagesContainer?.addEventListener('contextmenu', (e: MouseEvent) => {
   const target = e.target as HTMLElement | null;
   const item = target?.closest<HTMLElement>('.message-item[data-msg-key]');
   if (!item) return;

   e.preventDefault();
   const key = item.dataset.msgKey;
   const msg = allFilteredMessages.find(m => m.key === key);
   if (msg) {
      openContextMenu(e.clientX, e.clientY, msg);
   }
});

// Prevent mobile / touch text selection highlighting on message boxes
messagesContainer?.addEventListener('selectstart', (e: Event) => {
   const isTouchDevice = 'ontouchstart' in window || (navigator.maxTouchPoints != null && navigator.maxTouchPoints > 0);
   if (isTouchDevice && (e.target as HTMLElement | null)?.closest('.message-item')) {
      e.preventDefault();
   }
});

// Long-press on mobile touch devices
let touchTimer: number | null = null;
let touchStartX = 0;
let touchStartY = 0;
let isLongPressActive = false;

messagesContainer?.addEventListener('touchstart', (e: TouchEvent) => {
   if (e.touches.length !== 1) {
      if (touchTimer !== null) clearTimeout(touchTimer);
      touchTimer = null;
      return;
   }
   const touch = e.touches[0];
   const target = e.target as HTMLElement | null;
   const item = target?.closest<HTMLElement>('.message-item[data-msg-key]');
   if (!item) return;

   touchStartX = touch.clientX;
   touchStartY = touch.clientY;
   isLongPressActive = false;

   if (touchTimer !== null) clearTimeout(touchTimer);
   touchTimer = window.setTimeout(() => {
      isLongPressActive = true;
      try {
         window.getSelection()?.removeAllRanges();
      } catch {}
      const key = item.dataset.msgKey;
      const msg = allFilteredMessages.find(m => m.key === key);
      if (msg) {
         if ('vibrate' in navigator) {
            try { navigator.vibrate(35); } catch {}
         }
         openContextMenu(touchStartX, touchStartY, msg);
      }
   }, 450);
}, { passive: true });

messagesContainer?.addEventListener('touchmove', (e: TouchEvent) => {
   if (touchTimer === null) return;
   const touch = e.touches[0];
   if (!touch) return;
   const dx = Math.abs(touch.clientX - touchStartX);
   const dy = Math.abs(touch.clientY - touchStartY);
   if (dx > 10 || dy > 10) {
      clearTimeout(touchTimer);
      touchTimer = null;
   }
}, { passive: true });

messagesContainer?.addEventListener('touchend', (e: TouchEvent) => {
   if (touchTimer !== null) {
      clearTimeout(touchTimer);
      touchTimer = null;
   }
   if (isLongPressActive) {
      e.preventDefault();
   }
});

messagesContainer?.addEventListener('touchcancel', () => {
   if (touchTimer !== null) {
      clearTimeout(touchTimer);
      touchTimer = null;
   }
});

messagesContainer?.addEventListener('click', (e: MouseEvent) => {
   if (isLongPressActive) {
      e.preventDefault();
      e.stopPropagation();
      isLongPressActive = false;
   }
}, true);

// Initialize viewer controllers
initImageZoomController();
initTextViewerControls();

// Cleanup media playback when switching tabs
onSectionShow('section-messages', cleanupViewerMedia);
onSectionShow('section-network', cleanupViewerMedia);
onSectionShow('section-identity', cleanupViewerMedia);
onSectionShow('section-peers', cleanupViewerMedia);
onSectionShow('section-settings', cleanupViewerMedia);
onSectionShow('section-help', cleanupViewerMedia);


