import { graffiti, IdentityEntry, PeerEntry, openPackFile } from './graffiti-api.js';
import { onSectionShow, onWsEvent, onWsOpen, showSection } from './app.js';

const form = document.getElementById('message-form') as HTMLFormElement | null;
const fromField = document.getElementById('from-field') as HTMLSelectElement | null;
const toField = document.getElementById('to-field') as HTMLSelectElement | null;
const messageText = document.getElementById('message-text') as HTMLTextAreaElement | null;
const sendFileButton = document.getElementById('send-file') as HTMLButtonElement | null;
const fileInput = document.getElementById('file-input') as HTMLInputElement | null;
const messagesSection = document.getElementById('section-messages') as HTMLElement | null;
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
const DEFAULT_ITEM_HEIGHT = 120;
const ITEM_GAP = 12; // 0.75rem flex gap in #messages
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

async function refreshMessages(): Promise<void> {
   if (isRefreshing) return;
   isRefreshing = true;
   try {
      await refreshNameMaps();
      const { messages } = await graffiti.listMessages();
      const container = document.getElementById('messages');
      if (!container) return;

      allFilteredMessages = messages;
      renderVirtualList();
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

function formatTime(created: number | string | null | undefined): string {
   if (created == null) return '';
   const d = new Date(Number(created));
   return isNaN(d.getTime()) ? String(created) : d.toLocaleString();
}

function formatSize(bytes: number | null | undefined): string {
   if (bytes == null) return '';
   if (bytes < 1024) return `${bytes} B`;
   if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
   return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ── Media type classification ──────────────────────────────────────────────────
const imageExtensions = new Set(['png', 'jpg', 'jpeg', 'avif', 'gif', 'bmp', 'webp', 'svg']);
const textExtensions = new Set(['txt']);
const audioExtensions = new Set(['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a']);
const videoExtensions = new Set(['mp4', 'webm', 'mkv', 'avi', 'mov']);

const isImage = (t: string) => imageExtensions.has(t);
const isText = (t: string) => textExtensions.has(t);
const isAudio = (t: string) => audioExtensions.has(t);
const isVideo = (t: string) => videoExtensions.has(t);

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
      if (iso !== 'Invalid Date') timeEl.dateTime = iso;
   }
}

function wireActions(item: HTMLElement, msg: MessageData): void {
   item.querySelector<HTMLButtonElement>('.msg-btn-delete')?.addEventListener('click', () => {
      graffiti.removeMessage(msg.key)
         .then(() => {
            currentMessages.delete(msg.key);
            itemHeights.delete(msg.key);
            textContentCache.delete(msg.key);
            allFilteredMessages = allFilteredMessages.filter(m => m.key !== msg.key);
            renderVirtualList();
         })
         .catch((err: Error) => setStatus(`Delete failed: ${err.message}`));
   });
}

function createMessageElement(msg: MessageData): HTMLElement | null {
   const url = graffiti.contentUrl(msg.key);
   const tpl = document.getElementById(pickTemplateId(msg.type)) as HTMLTemplateElement | null;
   if (!tpl) return null;

   const item = tpl.content.cloneNode(true) as DocumentFragment;
   const el = item.firstElementChild as HTMLElement;
   el.dataset.msgKey = msg.key;
   fillHeader(el, msg);
   wireActions(el, msg);

   if (isText(msg.type)) {
      const pre = el.querySelector<HTMLPreElement>('.msg-text-content');
      if (pre) {
         const renderText = (t: string) => {
            const isTruncated = (msg.size && msg.size > 512) || t.length > 512;
            if (isTruncated) {
               pre.innerHTML = renderMarkdown(t.slice(0, 512) + '…');
               let viewBtn = el.querySelector<HTMLButtonElement>('.btn-view-text');
               if (!viewBtn) {
                  viewBtn = document.createElement('button');
                  viewBtn.type = 'button';
                  viewBtn.className = 'btn-view-text btn-view-pack';
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
      }
   } else if (isAudio(msg.type)) {
      const fileNameEl = el.querySelector<HTMLElement>('.msg-file-name');
      if (fileNameEl) fileNameEl.textContent = msg.name || '';
      const audio = el.querySelector<HTMLAudioElement>('.msg-media');
      if (audio) audio.src = url;
   } else if (isVideo(msg.type)) {
      const fileNameEl = el.querySelector<HTMLElement>('.msg-file-name');
      if (fileNameEl) fileNameEl.textContent = msg.name || '';
      const video = el.querySelector<HTMLVideoElement>('.msg-media');
      if (video) video.src = url;
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

      if (msg.name && (msg.name.toLowerCase().endsWith('.pack') || msg.name.toLowerCase().endsWith('.epack'))) {
         const viewBtn = document.createElement('button');
         viewBtn.type = 'button';
         viewBtn.className = 'btn-view-pack';
         viewBtn.textContent = '▶ View Pack';
         viewBtn.style.marginLeft = '8px';
         viewBtn.addEventListener('click', (e) => {
            e.preventDefault();
            void openPackFile({ encKey: msg.key, name: msg.name });
         });
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
   setStatus(`Sending ${payload.type}…`);
   try {
      const { identityKey, peerKey } = payload;
      if (!identityKey || !peerKey) throw new Error('Select a sender and recipient first.');
      if (payload.type === 'text') {
         await graffiti.sendText(identityKey, peerKey, payload.text);
      } else {
         await graffiti.sendFile(identityKey, peerKey, payload.file);
      }
      setStatus(`${payload.type} sent.`);
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
   }
});

messageText?.addEventListener('input', () => autoResizeTextarea(messageText));

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



// ── Bootstrap ─────────────────────────────────────────────────────────────────
setStatus('Ready');
autoResizeTextarea(messageText);
onSectionShow('section-messages', () => {
   void populateSelects();
   void queueRefreshMessages();
});

onWsOpen(() => {
   void populateSelects();
   void queueRefreshMessages();
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

export function openFullContentViewer(msg: MessageData, fullText?: string): void {
   const url = graffiti.contentUrl(msg.key);
   const filenameEl = document.getElementById('view-content-filename');
   const metaEl = document.getElementById('view-content-meta');
   const downloadBtn = document.getElementById('btn-download-view-content') as HTMLAnchorElement | null;
   const textPre = document.getElementById('view-content-text');

   const authorLabel = msg.author || 'Unknown';
   const recipientLabel = msg.recipient || 'Unknown';
   const sizeStr = msg.size ? ` • ${formatSize(msg.size)}` : '';
   const timeStr = msg.created ? ` • ${formatTime(msg.created)}` : '';

   if (filenameEl) {
      filenameEl.textContent = msg.name || 'Text Message';
   }
   if (metaEl) {
      metaEl.textContent = `From ${authorLabel} to ${recipientLabel}${sizeStr}${timeStr}`;
   }
   if (downloadBtn) {
      downloadBtn.href = url;
      downloadBtn.download = msg.name || 'message.txt';
   }

   if (textPre) {
      if (fullText !== undefined && fullText !== '') {
         textPre.innerHTML = renderMarkdown(fullText);
      } else {
         const cached = textContentCache.get(msg.key);
         if (cached !== undefined) {
            textPre.innerHTML = renderMarkdown(cached);
         } else {
            textPre.textContent = 'Loading full message content…';
            fetch(url)
               .then(r => r.text())
               .then(t => {
                  textContentCache.set(msg.key, t);
                  textPre.innerHTML = renderMarkdown(t);
               })
               .catch((err: Error) => {
                  textPre.textContent = `[Error loading content: ${err.message}]`;
               });
         }
      }
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

   for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      if (line.includes('@@@CODEBLOCK_')) {
         if (inList) {
            result.push(listType === 'ul' ? '</ul>' : '</ol>');
            inList = false;
            listType = null;
         }
         result.push(line);
         continue;
      }

      const h3Match = line.match(/^###\s+(.+)$/);
      const h2Match = line.match(/^##\s+(.+)$/);
      const h1Match = line.match(/^#\s+(.+)$/);
      const ulMatch = line.match(/^[\*\-\+]\s+(.+)$/);
      const olMatch = line.match(/^\d+\.\s+(.+)$/);
      const bqMatch = line.match(/^&gt;\s+(.+)$/);

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
      } else if (bqMatch) {
         result.push(`<blockquote class="msg-blockquote">${inlineFormat(bqMatch[1])}</blockquote>`);
      } else if (line.trim() === '') {
         result.push('<div class="msg-spacer"></div>');
      } else {
         result.push(`<p class="msg-para">${inlineFormat(line)}</p>`);
      }
   }

   if (inList) {
      result.push(listType === 'ul' ? '</ul>' : '</ol>');
   }

   let finalHtml = result.join('');
   finalHtml = finalHtml.replace(/@@@CODEBLOCK_(\d+)@@@/g, (_m, idx) => codeBlocks[Number(idx)] || '');
   return finalHtml;
}

document.addEventListener('click', (e: MouseEvent) => {
   const target = e.target as HTMLElement | null;
   if (!target) return;

   const viewBtn = target.closest<HTMLButtonElement>('.btn-view-text');
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

   const closeBtn = target.closest('#btn-close-view-content');
   if (closeBtn) {
      e.preventDefault();
      e.stopPropagation();
      showSection('section-messages');
      return;
   }
});

