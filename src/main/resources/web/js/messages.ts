import {graffiti, IdentityEntry, openPackFile, PeerEntry} from './graffiti-api.js';
import {onSectionShow, onWsEvent, onWsOpen, showSection} from './app.js';
import {showDialog, showProgressModal} from './dialog.js';

const form = document.getElementById('message-form') as HTMLFormElement | null;
const fromField = document.getElementById('from-field') as HTMLSelectElement | null;
const toField = document.getElementById('to-field') as HTMLSelectElement | null;
const messageText = document.getElementById('message-text') as HTMLTextAreaElement | null;
const sendFileButton = document.getElementById('send-file') as HTMLButtonElement | null;
const urgentCheckbox = document.getElementById('urgent-checkbox') as HTMLInputElement | null;

function resetUrgentCheckbox(): void {
   if (urgentCheckbox) {
      urgentCheckbox.checked = false;
      urgentCheckbox.closest('.urgent-toggle')?.classList.remove('is-active');
   }
}

urgentCheckbox?.addEventListener('change', () => {
   urgentCheckbox.closest('.urgent-toggle')?.classList.toggle('is-active', urgentCheckbox.checked);
});
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
      const [{identities}, {peers}] = await Promise.all([
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

// ── Windowed Message List State ─────────────────────────────────────────────
const PAGE_BATCH_SIZE = 20;
let visibleBatchCount = PAGE_BATCH_SIZE;
let isPrepending = false;

const textContentCache = new Map<string, string>();
let allRawMessages: MessageData[] = [];
let allFilteredMessages: MessageData[] = [];

// ── LRU Message DOM Element Cache ──────────────────────────────────────────
const MAX_CACHED_ELEMENTS = 300;
const messageElementCache = new Map<string, HTMLElement>();

function getCachedElement(key: string): HTMLElement | null {
   const el = messageElementCache.get(key);
   if (el) {
      // Refresh recency in Map insertion order
      messageElementCache.delete(key);
      messageElementCache.set(key, el);
   }
   if (el) {
      return el
   }
   return null;
}

function putCachedElement(key: string, el: HTMLElement): void {
   if (messageElementCache.has(key)) {
      messageElementCache.delete(key);
   } else if (messageElementCache.size >= MAX_CACHED_ELEMENTS) {
      // Evict oldest entry that is NOT currently connected to the DOM
      for (const [k, oldEl] of messageElementCache.entries()) {
         if (!oldEl.isConnected) {
            messageElementCache.delete(k);
            break;
         }
      }
   }
   messageElementCache.set(key, el);
}

function evictCachedElement(key: string): void {
   messageElementCache.delete(key);
}

function isMessageInSelectedTopic(msg: MessageData, selectedToKey: string): boolean {
   if (!selectedToKey) return false;
   const recKey = msg.recipientKey || (msg.recipient ? nameToKey.get(msg.recipient) : '');
   if (recKey && recKey === selectedToKey) return true;

   const iden = knownIdentities.find(id => id.peerKey === selectedToKey || id.key === selectedToKey);
   if (iden) {
      if (recKey && (recKey === iden.key || recKey === iden.peerKey)) return true;
      if (msg.recipient && msg.recipient === iden.name) return true;
   }

   const peer = knownPeers.find(p => p.key === selectedToKey);
   if (peer) {
      if (recKey && recKey === peer.key) return true;
      if (msg.recipient && msg.recipient === peer.name) return true;
   }

   return false;
}

// ── Topic Last Viewed & Unread Badges State ─────────────────────────────────
let topicLastViewed: Record<string, number> = {};
let saveTopicLastViewedTimer: number | null = null;
let activeToKey: string = '';

async function loadTopicLastViewed(): Promise<void> {
   try {
      const raw = await graffiti.getStore('graffiti:topic-last-viewed');
      if (raw) {
         const parsed = JSON.parse(raw);
         if (parsed && typeof parsed === 'object') {
            topicLastViewed = parsed;
         }
      }
   } catch (e) {
      console.warn('Failed to load topic-last-viewed:', e);
   }
}

function scheduleSaveTopicLastViewed(): void {
   if (saveTopicLastViewedTimer !== null) {
      window.clearTimeout(saveTopicLastViewedTimer);
   }
   saveTopicLastViewedTimer = window.setTimeout(async () => {
      saveTopicLastViewedTimer = null;
      try {
         await graffiti.setStore('graffiti:topic-last-viewed', JSON.stringify(topicLastViewed));
      } catch (e) {
         console.warn('Failed to save topic-last-viewed:', e);
      }
   }, 300);
}

function markTopicRead(key: string, timestamp?: number): void {
   if (!key) return;
   let ts = timestamp ?? Date.now();
   if (!timestamp) {
      for (const msg of allRawMessages) {
         if (isMessageInSelectedTopic(msg, key)) {
            const msgTime = typeof msg.fileTime === 'number' && msg.fileTime > 0
               ? msg.fileTime
               : Number(msg.created || 0);
            if (msgTime > ts) {
               ts = msgTime;
            }
         }
      }
   }
   const current = topicLastViewed[key] ?? 0;
   if (ts > current) {
      topicLastViewed[key] = ts;
      scheduleSaveTopicLastViewed();
   }
}

export function updateUnreadBadges(): void {
   if (!toField) return;
   const currentSelected = toField.value;
   let totalUnread = 0;

   for (const opt of Array.from(toField.options)) {
      const key = opt.value;
      const baseName = opt.dataset.baseName || opt.textContent || '';
      if (!key) continue;

      if (key === currentSelected) {
         opt.textContent = baseName;
      } else {
         const lastViewed = topicLastViewed[key] ?? 0;
         let unreadCount = 0;
         for (const msg of allRawMessages) {
            if (isMessageInSelectedTopic(msg, key)) {
               const msgTime = typeof msg.fileTime === 'number' && msg.fileTime > 0
                  ? msg.fileTime
                  : Number(msg.created || 0);
               if (msgTime > lastViewed) {
                  unreadCount++;
               }
            }
         }
         if (unreadCount > 0) {
            opt.textContent = `${baseName} (${unreadCount})`;
            totalUnread += unreadCount;
         } else {
            opt.textContent = baseName;
         }
      }
   }

   const navMsgLabel = document.getElementById('nav-msg-label');
   if (navMsgLabel) {
      navMsgLabel.textContent = totalUnread > 0 ? `Msg (${totalUnread})` : 'Msg';
   }
}

function updateFilteredMessages(): void {
   const selectedTo = toField?.value ?? '';
   if (!selectedTo) {
      allFilteredMessages = [];
   } else {
      allFilteredMessages = allRawMessages.filter(m => isMessageInSelectedTopic(m, selectedTo));
   }
}

export function applyTopicFilter(shouldScrollToBottom = true): void {
   visibleBatchCount = PAGE_BATCH_SIZE;
   updateFilteredMessages();
   renderMessageList();
   if (shouldScrollToBottom) {
      scrollToBottom();
   }
}

export function renderMessageList(): void {
   const container = document.getElementById('messages');
   if (!container) return;

   if (allFilteredMessages.length === 0) {
      container.innerHTML = '';
      currentMessages.clear();
      let emptyEl = document.getElementById('messages-empty');
      if (!emptyEl) {
         emptyEl = document.createElement('div');
         emptyEl.id = 'messages-empty';
         emptyEl.className = 'empty-row';
         container.appendChild(emptyEl);
      }
      const selectedTo = toField?.value;
      if (!selectedTo) {
         emptyEl.textContent = 'No topic or recipient selected.';
      } else {
         const selectedName = toField?.selectedOptions?.[0]?.textContent || 'this topic';
         emptyEl.textContent = `No messages in ${selectedName} yet.`;
      }
      return;
   }

   document.getElementById('messages-empty')?.remove();

   const startIndex = Math.max(0, allFilteredMessages.length - visibleBatchCount);
   const visibleSlice = allFilteredMessages.slice(startIndex);
   const visibleElements: HTMLElement[] = [];

   for (const msg of visibleSlice) {
      let el: HTMLElement | null = container.querySelector(`[data-msg-key="${CSS.escape(msg.key)}"]`);
      if (el) {
         fillHeader(el, msg);
         getCachedElement(msg.key);
      } else {
         el = getCachedElement(msg.key);
         if (el) {
            fillHeader(el, msg);
         } else {
            el = createMessageElement(msg);
            if (el) {
               putCachedElement(msg.key, el);
            }
         }
      }
      if (el) {
         visibleElements.push(el);
      }
   }

   const keepSet = new Set<HTMLElement>(visibleElements);
   const children = Array.from(container.children) as HTMLElement[];
   for (const child of children) {
      if (!keepSet.has(child) && child.id !== 'messages-empty') {
         child.remove();
         const key = child.dataset.msgKey;
         if (key) currentMessages.delete(key);
      }
   }

   let refNode: Node | null = null;
   for (let i = visibleElements.length - 1; i >= 0; i--) {
      const el = visibleElements[i];
      if (el.parentElement !== container || el.nextElementSibling !== refNode) {
         container.insertBefore(el, refNode);
      }
      refNode = el;
   }

   currentMessages.clear();
   for (const msg of visibleSlice) {
      currentMessages.add(msg.key);
   }
}

function checkAndPrependHistory(): void {
   const section = document.getElementById('section-messages');
   if (!section || !section.classList.contains('is-active')) return;
   if (isPrepending) return;
   if (visibleBatchCount >= allFilteredMessages.length) return;

   if (window.scrollY < 250) {
      isPrepending = true;
      const prevScrollHeight = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight);
      const prevScrollY = window.scrollY;

      visibleBatchCount = Math.min(allFilteredMessages.length, visibleBatchCount + PAGE_BATCH_SIZE);
      renderMessageList();

      const newScrollHeight = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight);
      const heightDelta = newScrollHeight - prevScrollHeight;
      window.scrollTo(0, prevScrollY + heightDelta);

      requestAnimationFrame(() => {
         isPrepending = false;
      });
   }
}

window.addEventListener('scroll', checkAndPrependHistory, {passive: true});

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
      await loadTopicLastViewed();
      const {messages} = await graffiti.listMessages();
      const container = document.getElementById('messages');
      if (!container) return;

      const wasAtBottom = isNearBottom();
      allRawMessages = messages;
      const currentSelected = toField?.value ?? '';
      if (currentSelected) {
         markTopicRead(currentSelected);
      }
      updateFilteredMessages();
      renderMessageList();
      updateUnreadBadges();
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

const isImage = (t: string) => imageExtensions.has(t.toLowerCase());
const isText = (t: string) => textExtensions.has(t.toLowerCase());
const isAudio = (t: string) => audioExtensions.has(t.toLowerCase());
const isVideo = (t: string) => videoExtensions.has(t.toLowerCase());
const isHtml = (t: string) => htmlExtensions.has(t.toLowerCase());

function pickTemplateId(type: string): string {
   if (type === 'bell') return 'tpl-bell-message';
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
   fileTime?: number;
   urgent?: boolean;
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
      const displayTime = typeof msg.fileTime === 'number' && msg.fileTime > 0
         ? msg.fileTime
         : (msg.created ? Number(msg.created) : Date.now());
      timeEl.textContent = formatTime(displayTime);
      const iso = new Date(displayTime).toISOString();
      if (iso !== 'Invalid Date') {
         timeEl.dateTime = iso;
         timeEl.title = new Date(displayTime).toLocaleString();
      }
   }

   if (msg.urgent) {
      item.classList.add('urgent-message');
      if (!item.querySelector('.msg-urgent-badge')) {
         const badge = document.createElement('span');
         badge.className = 'msg-urgent-badge';
         badge.textContent = '⚠️ Urgent';
         const header = item.querySelector('.message-header');
         if (header && timeEl) {
            header.insertBefore(badge, timeEl);
         } else if (header) {
            header.appendChild(badge);
         }
      }
   } else {
      item.classList.remove('urgent-message');
      item.querySelector('.msg-urgent-badge')?.remove();
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

async function handleMessageInfo(msg: MessageData): Promise<void> {
   await showDialog({
      title: 'Content Info',
      templateId: 'tpl-message-info',
      confirmLabel: 'Close',
      init(body) {
         const cancelBtn = body.closest('dialog')?.querySelector<HTMLButtonElement>('.app-dialog-cancel');
         if (cancelBtn) {
            cancelBtn.style.display = 'none';
         }

         const nameEl = body.querySelector('#msg-info-name');
         const sizeEl = body.querySelector('#msg-info-size');
         const typeEl = body.querySelector('#msg-info-type');
         const authorEl = body.querySelector('#msg-info-author');
         const recipientEl = body.querySelector('#msg-info-recipient');
         const fileTimeEl = body.querySelector('#msg-info-filetime');
         const createdEl = body.querySelector('#msg-info-created');
         const keyEl = body.querySelector('#msg-info-key');

         if (nameEl) nameEl.textContent = msg.name || 'Untitled';
         if (sizeEl) sizeEl.textContent = typeof msg.size === 'number' ? `${formatSize(msg.size)} (${msg.size.toLocaleString()} bytes)` : '—';
         if (typeEl) typeEl.textContent = msg.type || 'unknown';
         if (authorEl) authorEl.textContent = msg.authorKey ? `${msg.author || 'Unknown'} (${msg.authorKey})` : (msg.author || 'Unknown');
         if (recipientEl) recipientEl.textContent = msg.recipientKey ? `${msg.recipient || 'Unknown'} (${msg.recipientKey})` : (msg.recipient || 'Unknown');

         const fileTime = typeof msg.fileTime === 'number' && msg.fileTime > 0 ? msg.fileTime : null;
         if (fileTimeEl) {
            fileTimeEl.textContent = fileTime ? new Date(fileTime).toLocaleString() : '—';
         }

         const createdTime = msg.created ? Number(msg.created) : null;
         if (createdEl) {
            createdEl.textContent = createdTime && !isNaN(createdTime) ? new Date(createdTime).toLocaleString() : '—';
         }

         if (keyEl) keyEl.textContent = msg.key;
      }
   });
}

async function handleForward(msg: MessageData): Promise<void> {
   const fromKey = fromField?.value;
   if (!fromKey) {
      setStatus('Select a sender identity first');
      alert('Please select a sender identity first.');
      return;
   }

   const msgRecipientKey = msg.recipientKey || (msg.recipient ? nameToKey.get(msg.recipient) : '');

   const data = await showDialog({
      title: 'Forward Message',
      templateId: 'tpl-message-forward',
      confirmLabel: 'Forward',
      init: (body) => {
         const select = body.querySelector<HTMLSelectElement>('#dlg-forward-to');
         if (!select) return;
         select.replaceChildren();

         const seenKeys = new Set<string>();
         const optGroupIdentities = document.createElement('optgroup');
         optGroupIdentities.label = 'Topic Identities';
         let idCount = 0;
         for (const id of knownIdentities) {
            if (id.peerKey !== msgRecipientKey && id.key !== msgRecipientKey && !seenKeys.has(id.peerKey)) {
               seenKeys.add(id.peerKey);
               const opt = document.createElement('option');
               opt.value = id.peerKey;
               opt.textContent = `🏷️ ${id.name}`;
               opt.title = 'Topic Identity (Shared forum)';
               optGroupIdentities.append(opt);
               idCount++;
            }
         }
         if (idCount > 0) select.append(optGroupIdentities);

         const optGroupPeers = document.createElement('optgroup');
         optGroupPeers.label = 'Peers';
         let peerCount = 0;
         for (const peer of knownPeers) {
            if (peer.key !== msgRecipientKey && !seenKeys.has(peer.key)) {
               seenKeys.add(peer.key);
               const opt = document.createElement('option');
               opt.value = peer.key;
               opt.textContent = `👤 ${peer.name}`;
               opt.title = 'Peer (Direct message to friend)';
               optGroupPeers.append(opt);
               peerCount++;
            }
         }
         if (peerCount > 0) select.append(optGroupPeers);
      }
   });

   if (!data || !data.targetPeer) return;
   const destKey = data.targetPeer;

   const isUrgent = urgentCheckbox?.checked ?? false;
   setStatus('Forwarding message…');
   try {
      await graffiti.forwardMessage(msg.key, fromKey, destKey, isUrgent);
      resetUrgentCheckbox();
      setStatus('Message forwarded.');
      if (toField) {
         const prevKey = activeToKey;
         if (prevKey && prevKey !== destKey) {
            markTopicRead(prevKey);
         }
         toField.value = destKey;
         activeToKey = destKey;
         markTopicRead(destKey);
         updateSameAuthorRecipientWarning();
         void saveOrClearRememberedFields();
         applyTopicFilter(true);
         updateUnreadBadges();
      }
      queueRefreshMessages();
   } catch (err: any) {
      setStatus(`Forward failed: ${err?.message || err}`);
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
      autoResizeTextarea(messageText);
      updateComposerHeight();
      const wasAtBottom = isNearBottom();
      if (wasAtBottom) {
         scrollToBottom();
      }
      messageText.focus();
      messageText.setSelectionRange(messageText.value.length, messageText.value.length);
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
      if (audio) {
         audio.preload = 'none';
         if (audio.src !== url) audio.src = url;
      }
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
      if (video) {
         video.preload = 'metadata';
         const videoUrl = `${url}#t=0.001`;
         if (video.src !== videoUrl) video.src = videoUrl;
      }
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
         viewBtn.innerHTML = isPackFile
            ? '<span class="material-symbols-outlined" style="font-size: 1rem;">open_in_new</span> Launch'
            : '<span class="material-symbols-outlined" style="font-size: 1rem;">visibility</span> View';
         viewBtn.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            if (isPackFile) {
               void openPackFile({encKey: msg.key, name: msg.name});
            } else {
               openFullContentViewer(msg);
            }
         };
         link?.parentElement?.appendChild(viewBtn);
      }
   }

   return el;
}

function displayMessage(msg: MessageData): void {
   allFilteredMessages.push(msg);
   visibleBatchCount++;
   renderMessageList();
}

async function populateSelects(): Promise<void> {
   const [{identities}, {peers}, node] = await Promise.all([
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
      loadTopicLastViewed(),
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
      const seenKeys = new Set<string>();
      const optGroupTopics = document.createElement('optgroup');
      optGroupTopics.label = 'Topic Identities';
      for (const id of identities) {
         if (!seenKeys.has(id.peerKey)) {
            seenKeys.add(id.peerKey);
            const opt = document.createElement('option');
            opt.value = id.peerKey;   // PeerKey, not IdentityKey
            const baseText = `🏷️ ${id.name}`;
            opt.dataset.baseName = baseText;
            opt.textContent = baseText;
            opt.title = 'Topic Identity (Shared topic/forum)';
            optGroupTopics.append(opt);
         }
      }
      if (optGroupTopics.children.length > 0) {
         toField.append(optGroupTopics);
      }

      const optGroupPeers = document.createElement('optgroup');
      optGroupPeers.label = 'Peers';
      for (const peer of peers) {
         if (!seenKeys.has(peer.key)) {
            seenKeys.add(peer.key);
            const opt = document.createElement('option');
            opt.value = peer.key;
            const baseText = `👤 ${peer.name}`;
            opt.dataset.baseName = baseText;
            opt.textContent = baseText;
            opt.title = 'Peer (Direct message to friend)';
            optGroupPeers.append(opt);
         }
      }
      if (optGroupPeers.children.length > 0) {
         toField.append(optGroupPeers);
      }
      if (seenKeys.size === 0) {
         toField.style.display = 'none';
         if (toEmptyMsg) {
            toEmptyMsg.textContent = 'No peers or identities available';
            toEmptyMsg.style.display = '';
         }
      } else {
         toField.style.display = '';
         if (toEmptyMsg) toEmptyMsg.style.display = 'none';
         if (prevTo && seenKeys.has(prevTo)) {
            toField.value = prevTo;
         } else if (savedToKey && seenKeys.has(savedToKey) && isSavedRecipient(savedToKey, identities, peers)) {
            toField.value = savedToKey;
         }
      }
   }
   activeToKey = toField?.value ?? '';
   if (activeToKey) {
      markTopicRead(activeToKey);
   }
   updateSameAuthorRecipientWarning();
   void saveOrClearRememberedFields();
   applyTopicFilter(false);
   updateUnreadBadges();
}

function getEnvelope(): { identityKey: string; peerKey: string } {
   return {
      identityKey: fromField?.value ?? '',
      peerKey: toField?.value ?? '',
   };
}

type Payload =
   | { type: 'text'; text: string; identityKey: string; peerKey: string; urgent?: boolean }
   | {
   type: 'file';
   fileName: string;
   file: File;
   identityKey: string;
   peerKey: string;
   source?: string;
   urgent?: boolean
};

async function sendPayload(payload: Payload): Promise<void> {
   if (isSending) {
      setStatus('Send in progress. Only one item can be sent at a time.');
      return;
   }
   isSending = true;
   setStatus(`Sending ${payload.type}`);
   try {
      const {identityKey, peerKey} = payload;
      if (!identityKey || !peerKey) throw new Error('Select a sender and recipient first.');
      if (payload.type === 'text') {
         await graffiti.sendText(identityKey, peerKey, payload.text, !!payload.urgent);
      } else {
         await graffiti.sendFile(identityKey, peerKey, payload.file, !!payload.urgent);
      }
      resetUrgentCheckbox();
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
   if (dataTransfer.files?.length > 0) return {kind: 'file', value: dataTransfer.files[0]};
   const plain = dataTransfer.getData('text/plain');
   if (plain) return {kind: 'text', value: plain};
   const html = dataTransfer.getData('text/html');
   if (html) return {kind: 'html', value: html};
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
   const urgent = urgentCheckbox?.checked ?? false;
   await sendPayload({type: 'text', text, urgent, ...getEnvelope()});
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
   const prevKey = activeToKey;
   const newKey = toField?.value ?? '';
   if (prevKey && prevKey !== newKey) {
      markTopicRead(prevKey);
   }
   activeToKey = newKey;
   if (newKey) {
      markTopicRead(newKey);
   }
   updateSameAuthorRecipientWarning();
   void saveOrClearRememberedFields();
   applyTopicFilter(true);
   updateUnreadBadges();
});


interface DroppedItem {
   file: File;
   path: string;
}

async function extractDroppedEntries(dataTransfer: DataTransfer): Promise<{ items: DroppedItem[], defaultName?: string, isFolder: boolean }> {
   const items: DroppedItem[] = [];
   const dtItems = dataTransfer.items;
   let isFolder = false;
   let folderName: string | undefined;

   if (dtItems && dtItems.length > 0 && typeof (dtItems[0] as any).webkitGetAsEntry === 'function') {
      const entryPromises: Promise<void>[] = [];

      for (let i = 0; i < dtItems.length; i++) {
         const entry = (dtItems[i] as any).webkitGetAsEntry();
         if (!entry) continue;

         if (entry.isDirectory) {
            isFolder = true;
            if (!folderName) folderName = entry.name;
         }

         async function traverse(ent: any, currentPath: string): Promise<void> {
            if (ent.isFile) {
               const file = await new Promise<File>((resolve, reject) => ent.file(resolve, reject));
               const relPath = currentPath ? `${currentPath}/${file.name}` : file.name;
               items.push({ file, path: relPath });
            } else if (ent.isDirectory) {
               const reader = ent.createReader();
               const readAll = async (): Promise<any[]> => {
                  const all: any[] = [];
                  while (true) {
                     const batch = await new Promise<any[]>((resolve, reject) => reader.readEntries(resolve, reject));
                     if (!batch || batch.length === 0) break;
                     all.push(...batch);
                  }
                  return all;
               };
               const nextDir = currentPath ? `${currentPath}/${ent.name}` : ent.name;
               const children = await readAll();
               for (const child of children) {
                  await traverse(child, nextDir);
               }
            }
         }

         if (entry.isDirectory && dtItems.length === 1) {
            const reader = entry.createReader();
            const readAll = async (): Promise<any[]> => {
               const all: any[] = [];
               while (true) {
                  const batch = await new Promise<any[]>((resolve, reject) => reader.readEntries(resolve, reject));
                  if (!batch || batch.length === 0) break;
                  all.push(...batch);
               }
               return all;
            };
            entryPromises.push((async () => {
               const children = await readAll();
               for (const child of children) {
                  await traverse(child, "");
               }
            })());
         } else {
            entryPromises.push(traverse(entry, ""));
         }
      }

      await Promise.all(entryPromises);
   }

   if (items.length === 0 && dataTransfer.files && dataTransfer.files.length > 0) {
      for (let i = 0; i < dataTransfer.files.length; i++) {
         const file = dataTransfer.files[i];
         items.push({ file, path: file.name });
      }
   }

   return { items, defaultName: folderName, isFolder };
}

async function sendPackPipeline(
   fileList: DroppedItem[],
   packName: string,
   urgent: boolean,
   envelope: { identityKey: string, peerKey: string }
): Promise<void> {
   if (isSending) {
      setStatus('Send in progress. Only one item can be sent at a time.');
      return;
   }
   if (!envelope.identityKey || !envelope.peerKey) {
      setStatus('Select a sender and recipient first.');
      return;
   }
   if (fileList.length === 0) {
      setStatus('No files to package.');
      return;
   }

   isSending = true;
   setStatus(`Preparing pack: ${packName}...`);

   const progress = showProgressModal('Creating Pack Archive', `Starting upload for ${packName}...`);
   let sessionId: string | null = null;

   try {
      progress.update(`Starting pack session: ${packName}...`, 0);
      const beginRes = await graffiti.createPackBegin(envelope.identityKey, envelope.peerKey, packName, urgent);
      sessionId = beginRes.sessionId;

      const total = fileList.length;
      for (let i = 0; i < total; i++) {
         const item = fileList[i];
         const pct = Math.round((i / total) * 100);
         progress.update(
            `Staging file ${i + 1} of ${total} (${pct}%)`,
            pct,
            item.path
         );
         setStatus(`Staging pack: ${i + 1}/${total} files...`);
         await graffiti.uploadPackFile(sessionId, item.path, item.file);
      }

      progress.update('Compiling pack archive on server...', 100, 'Writing pack index and entries');
      setStatus('Compiling pack archive...');

      await graffiti.createPackFinish(sessionId);

      progress.update('Pack sent successfully!', 100);
      setStatus('Pack sent.');
      resetUrgentCheckbox();
      shouldScrollToBottomOnSend = true;
      await refreshMessages();
      scrollToBottom();
   } catch (err) {
      const errMsg = (err as Error).message || 'Unknown error';
      setStatus(`Failed: ${errMsg}`);
      if (sessionId) {
         void graffiti.createPackCancel(sessionId).catch(() => {});
      }
      alert(`Pack creation error: ${errMsg}`);
   } finally {
      isSending = false;
      progress.close();
   }
}

async function promptPackNameAndSend(
   fileList: DroppedItem[],
   defaultName: string,
   urgent: boolean
): Promise<void> {
   const envelope = getEnvelope();
   if (!envelope.identityKey || !envelope.peerKey) {
      setStatus('Select a sender and recipient first.');
      return;
   }

   const initialName = defaultName.toLowerCase().endsWith('.pack') ? defaultName : `${defaultName}.pack`;

   const res = await showDialog({
      title: 'Create Pack Archive',
      templateId: 'tpl-pack-create-name',
      confirmLabel: 'Send Pack',
      init(body) {
         const input = body.querySelector<HTMLInputElement>('input[name="packName"]');
         if (input) {
            input.value = initialName;
            input.select();
         }
      }
   });

   if (!res || !res.packName || !res.packName.trim()) {
      setStatus('Pack creation cancelled.');
      return;
   }

   let packName = res.packName.trim();
   if (!packName.toLowerCase().endsWith('.pack')) {
      packName += '.pack';
   }

   await sendPackPipeline(fileList, packName, urgent, envelope);
}

fileInput?.addEventListener('change', async () => {
   const files = fileInput?.files;
   if (!files || files.length === 0) return;
   const urgent = urgentCheckbox?.checked ?? false;

   if (files.length === 1) {
      const file = files[0];
      await sendPayload({type: 'file', fileName: file.name, file, urgent, ...getEnvelope()});
      if (fileInput) fileInput.value = '';
      scrollToBottom();
      return;
   }

   // Multiple files selected: bundle into pack
   const items: DroppedItem[] = [];
   for (let i = 0; i < files.length; i++) {
      items.push({ file: files[i], path: files[i].name });
   }

   const firstBase = files[0].name.replace(/\.[^/.]+$/, '');
   const defaultName = `${firstBase}_pack.pack`;

   if (fileInput) fileInput.value = '';
   await promptPackNameAndSend(items, defaultName, urgent);
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

   if (!event.dataTransfer) {
      setStatus('Nothing to send from drop.');
      return;
   }

   const urgent = urgentCheckbox?.checked ?? false;

   // Check if files or folders were dropped
   const dropped = await extractDroppedEntries(event.dataTransfer);
   if (dropped.items.length > 0) {
      if (dropped.items.length === 1 && !dropped.isFolder) {
         const single = dropped.items[0];
         await sendPayload({type: 'file', fileName: single.file.name, file: single.file, urgent, ...getEnvelope()});
         return;
      }

      // Multiple files or folder dropped -> Create Pack
      const defaultName = dropped.defaultName
         ? dropped.defaultName
         : `${dropped.items[0].file.name.replace(/\.[^/.]+$/, '')}_pack`;

      await promptPackNameAndSend(dropped.items, defaultName, urgent);
      return;
   }

   // Plain text or HTML fallback
   const plain = event.dataTransfer.getData('text/plain');
   if (plain) {
      await sendPayload({type: 'text', text: plain, urgent, ...getEnvelope()});
      return;
   }
   const html = event.dataTransfer.getData('text/html');
   if (html) {
      await sendPayload({type: 'text', text: html, urgent, ...getEnvelope()});
      return;
   }

   setStatus('Nothing to send from drop.');
});

// ── Clipboard paste (files / screenshots) ────────────────────────────────────
messagesSection?.addEventListener('paste', async (event: ClipboardEvent) => {
   if (!event.clipboardData) return;

   const urgent = urgentCheckbox?.checked ?? false;

   const dropped = await extractDroppedEntries(event.clipboardData);
   if (dropped.items.length > 0) {
      event.preventDefault();

      if (dropped.items.length === 1 && !dropped.isFolder) {
         const single = dropped.items[0];
         await sendPayload({type: 'file', fileName: single.file.name, file: single.file, urgent, ...getEnvelope()});
         return;
      }

      // Multiple files or folder pasted -> Create Pack
      const defaultName = dropped.defaultName
         ? dropped.defaultName
         : `${dropped.items[0].file.name.replace(/\.[^/.]+$/, '')}_pack`;

      await promptPackNameAndSend(dropped.items, defaultName, urgent);
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
   }).observe(composerElement);
   updateComposerHeight();
}

onSectionShow('section-messages', () => {
   updateComposerHeight();
   shouldScrollToBottomOnLoad = true;
   void populateSelects();
   renderMessageList();
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
   if (msg.type === 'bell') return;
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
   document.addEventListener('click', requestPermission, {once: true});
}

// ── WebSocket hooks ───────────────────────────────────────────────────────────
onWsEvent('messages_update', async (msg: Record<string, unknown>) => {
   if (msg.action === 'remove') {
      const removedKey = msg.key as string;
      currentMessages.delete(removedKey);
      evictCachedElement(removedKey);
      allRawMessages = allRawMessages.filter(m => m.key !== removedKey);
      updateFilteredMessages();
      renderMessageList();
      updateUnreadBadges();
   } else if (msg.action === 'add') {
      const m = msg.msg as MessageData | undefined;
      if (m) {
         const currentSelected = toField?.value ?? '';
         if (currentSelected && isMessageInSelectedTopic(m, currentSelected)) {
            const msgTime = typeof m.fileTime === 'number' && m.fileTime > 0 ? m.fileTime : Number(m.created || 0);
            markTopicRead(currentSelected, Math.max(Date.now(), msgTime));
         }
         if (!currentMessages.has(m.key)) {
            notifyNewMessage(m);
         }
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
   }, {passive: false});

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
   }, {passive: false});

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
   }, {passive: false});
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
   const isPackFile = msg.name && (msg.name.toLowerCase().endsWith('.pack') || msg.name.toLowerCase().endsWith('.epack'));
   if (isPackFile) {
      void openPackFile({encKey: msg.key, name: msg.name});
      return;
   }

   currentViewerMsg = msg;
   const url = graffiti.contentUrl(msg.key);

   // Cleanup any currently playing media
   cleanupViewerMedia();

   // Containers
   const imgContainer = document.getElementById('view-content-image-container');
   const textContainer = document.getElementById('view-content-text-container');
   const videoContainer = document.getElementById('view-content-video-container');
   const audioContainer = document.getElementById('view-content-audio-container');
   const htmlContainer = document.getElementById('view-content-html-container');
   const genericContainer = document.getElementById('view-content-generic-container');

   if (imgContainer) imgContainer.style.display = 'none';
   if (textContainer) textContainer.style.display = 'none';
   if (videoContainer) videoContainer.style.display = 'none';
   if (audioContainer) audioContainer.style.display = 'none';
   if (htmlContainer) htmlContainer.style.display = 'none';
   if (genericContainer) genericContainer.style.display = 'none';

   const isHtmlFile = isHtml(msg.type) || (msg.name && (msg.name.toLowerCase().endsWith('.html') || msg.name.toLowerCase().endsWith('.htm')));

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
      const sizeStr = msg.size ? ` • ${formatSize(msg.size)}` : '';
      if (genericName) genericName.textContent = msg.name || 'File Attachment';
      if (genericMeta) genericMeta.textContent = `${msg.type ? `Type: ${msg.type.toUpperCase()}` : ''}${sizeStr}`;
   }

   showSection('section-view-content');
}

function escHtml(str: string): string {
   return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
}

function sanitizeUrl(url: string): string {
   const clean = url.trim().replace(/&amp;/g, '&');
   // Block dangerous protocol schemes like javascript:, vbscript:, data:
   if (/^\s*(javascript|vbscript|data):/i.test(clean)) {
      return '#';
   }
   // Allow standard web protocols, mailto, relative paths, anchor fragments, local files
   if (/^(https?:|mailto:|\/|\.\/|\.\.\/|#|[a-zA-Z0-9_-]+\.[a-zA-Z0-9]+)/i.test(clean)) {
      return clean;
   }
   // Relative path without known protocol
   if (!/^[a-zA-Z0-9+.-]+:/.test(clean)) {
      return clean;
   }
   return '#';
}

function inlineFormat(text: string): string {
   // Bold & Italic (Asterisks & Underscores)
   // Triple: ***bold-italic*** or ___bold-italic___
   return text
      .replace(/\*\*\*([^\*]+)\*\*\*/g, '<strong><em>$1</em></strong>')
      .replace(/(^|[^\w])___([^_]+)___(?=[^\w]|$)/g, '$1<strong><em>$2</em></strong>')
      // Double: **bold** or __bold__
      .replace(/\*\*([^\*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[^\w])__([^_]+)__(?=[^\w]|$)/g, '$1<strong>$2</strong>')
      // Single: *italic* or _italic_
      .replace(/(^|[^\*])\*([^\*]+)\*(?!\*)/g, '$1<em>$2</em>')
      .replace(/(^|[^\w])_([^_]+)_(?=[^\w]|$)/g, '$1<em>$2</em>')
      // Strikethrough: ~~text~~
      .replace(/~~([^~]+)~~/g, '<del>$1</del>');

}

export function renderMarkdown(raw: string): string {
   if (!raw) return '';

   // 1. Stash code blocks and inline code BEFORE general escaping and block processing
   const codeBlocks: string[] = [];
   const inlineCodes: string[] = [];

   // Fenced code blocks with optional language
   let text = raw.replace(/```([a-zA-Z0-9_-]*)\n?([\s\S]*?)```/g, (_m, lang, code) => {
      const idx = codeBlocks.length;
      const langClass = lang ? ` class="language-${escHtml(lang)}"` : '';
      codeBlocks.push(`<pre class="msg-code-block"><code${langClass}>${escHtml(code.replace(/\n$/, ''))}</code></pre>`);
      return `\n\n@@@CODE_BLOCK_${idx}@@@\n\n`;
   });

   // Inline code
   text = text.replace(/`([^`\n]+)`/g, (_m, code) => {
      const idx = inlineCodes.length;
      inlineCodes.push(`<code>${escHtml(code)}</code>`);
      return `@@@INLINE_CODE_${idx}@@@`;
   });

   // 2. Process block elements line-by-line
   const lines = text.split('\n');
   const result: string[] = [];

   let listType: 'ul' | 'ol' | null = null;
   let bqLines: string[] = [];
   let paraLines: string[] = [];

   const flushList = () => {
      if (listType) {
         result.push(listType === 'ul' ? '</ul>' : '</ol>');
         listType = null;
      }
   };

   const flushBq = () => {
      if (bqLines.length > 0) {
         result.push(`<blockquote class="msg-blockquote">${bqLines.map(l => inlineFormat(escHtml(l))).join('<br>')}</blockquote>`);
         bqLines = [];
      }
   };

   const flushPara = () => {
      if (paraLines.length > 0) {
         result.push(`<p class="msg-para">${paraLines.map(l => inlineFormat(escHtml(l))).join('<br>')}</p>`);
         paraLines = [];
      }
   };

   const flushAll = () => {
      flushList();
      flushBq();
      flushPara();
   };

   for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      // Placeholder check
      const cbMatch = line.trim().match(/^@@@CODE_BLOCK_(\d+)@@@$/);
      if (cbMatch) {
         flushAll();
         result.push(codeBlocks[Number(cbMatch[1])]);
         continue;
      }

      // Blank line terminates active blocks
      if (line.trim() === '') {
         flushAll();
         continue;
      }

      // Blockquote
      const bqMatch = line.match(/^>\s?(.*)$/);
      if (bqMatch) {
         flushList();
         flushPara();
         bqLines.push(bqMatch[1]);
         continue;
      } else {
         flushBq();
      }

      // Lists
      const ulMatch = line.match(/^[\*\-\+]\s+(.+)$/);
      const olMatch = line.match(/^\d+\.\s+(.+)$/);
      if (ulMatch || olMatch) {
         flushPara();
         const currentType = ulMatch ? 'ul' : 'ol';
         const itemText = ulMatch ? ulMatch[1] : olMatch![1];

         if (listType !== currentType) {
            flushList();
            result.push(currentType === 'ul' ? '<ul class="msg-list">' : '<ol class="msg-list">');
            listType = currentType;
         }
         result.push(`<li>${inlineFormat(escHtml(itemText))}</li>`);
         continue;
      } else {
         flushList();
      }

      // Headings
      const hMatch = line.match(/^(#{1,6})\s+(.+)$/);
      if (hMatch) {
         flushPara();
         const level = hMatch[1].length;
         result.push(`<h${level} class="msg-h${level}">${inlineFormat(escHtml(hMatch[2]))}</h${level}>`);
         continue;
      }

      // Standard paragraph line (buffered)
      paraLines.push(line.trim());
   }

   flushAll();

   // 3. Restore inline code
   return result.join('').replace(/@@@INLINE_CODE_(\d+)@@@/g, (_m, idx) => inlineCodes[Number(idx)] || '');
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
            const isPack = msg.name && (msg.name.toLowerCase().endsWith('.pack') || msg.name.toLowerCase().endsWith('.epack'));
            if (isPack) {
               void openPackFile({encKey: msg.key, name: msg.name});
            } else {
               openFullContentViewer(msg, textContentCache.get(key));
            }
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

// Escape key to exit fullscreen viewer
window.addEventListener('keydown', (e: KeyboardEvent) => {
   if (e.key === 'Escape') {
      const viewerSection = document.getElementById('section-view-content');
      if (viewerSection?.classList.contains('is-active')) {
         e.preventDefault();
         cleanupViewerMedia();
         showSection('section-messages');
      }
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
   } else if (action === 'forward') {
      await handleForward(msg);
   } else if (action === 'delete') {
      try {
         await graffiti.removeMessage(msg.key);
         currentMessages.delete(msg.key);
         textContentCache.delete(msg.key);
         evictCachedElement(msg.key);
         allRawMessages = allRawMessages.filter(m => m.key !== msg.key);
         updateFilteredMessages();
         renderMessageList();
      } catch (err: any) {
         setStatus(`Delete failed: ${err?.message || err}`);
      }
   } else if (action === 'quote' || action === 'reply') {
      messageText?.focus();
      await handleQuote(msg);
   } else if (action === 'copy') {
      await handleCopy(msg);
   } else if (action === 'download') {
      handleDownload(msg);
   } else if (action === 'info') {
      void handleMessageInfo(msg);
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
}, {passive: true});

window.addEventListener('resize', () => {
   if (msgContextMenu && !msgContextMenu.hidden) {
      closeContextMenu();
   }
}, {passive: true});

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
      } catch {
      }
      const key = item.dataset.msgKey;
      const msg = allFilteredMessages.find(m => m.key === key);
      if (msg) {
         if ('vibrate' in navigator) {
            try {
               navigator.vibrate(35);
            } catch {
            }
         }
         openContextMenu(touchStartX, touchStartY, msg);
      }
   }, 450);
}, {passive: true});

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
}, {passive: true});

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
onSectionShow('section-settings', cleanupViewerMedia);
onSectionShow('section-help', cleanupViewerMedia);


