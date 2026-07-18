import { graffiti, IdentityEntry } from './graffiti-api.js';
import { onSectionShow, onWsEvent, showSection } from './app.js';

const form = document.getElementById('message-form') as HTMLFormElement | null;
const fromField = document.getElementById('from-field') as HTMLSelectElement | null;
const toField = document.getElementById('to-field') as HTMLSelectElement | null;
const messageText = document.getElementById('message-text') as HTMLTextAreaElement | null;
const sendFileButton = document.getElementById('send-file') as HTMLButtonElement | null;
const fileInput = document.getElementById('file-input') as HTMLInputElement | null;
const messagesSection = document.getElementById('section-messages') as HTMLElement | null;
const statusEl = document.getElementById('send-status') as HTMLElement | null;
const refreshBtn = document.getElementById('btn-messages-refresh') as HTMLButtonElement | null;

let isSending = false;
let isRefreshing = false;

/** Tracks keys already rendered so WS-triggered refreshes don't duplicate rows. */
const currentMessages = new Set<string>();

// ── Name / avatar lookup map ──────────────────────────────────────────────────
/** Maps a display-name to its full key string for all known identities + peers. */
const nameToKey = new Map<string, string>();
let knownIdentities: IdentityEntry[] = [];
const activeFilterKeys = new Set<string>();
const seenIdentityKeys = new Set<string>();

async function refreshNameMaps(): Promise<void> {
   try {
      const [{ identities }, { peers }] = await Promise.all([
         graffiti.listIdentities(),
         graffiti.listPeers(),
      ]);
      knownIdentities = identities;
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

async function populateIdentityFilters(): Promise<void> {
   const { identities } = await graffiti.listIdentities();
   const filterList = document.getElementById('identities-filter-list');
   if (!filterList) return;

   const currentKeys = new Set(identities.map(id => id.key));

   // Sync existing filter keys to ensure we don't keep deleted identities
   for (const key of activeFilterKeys) {
      if (!currentKeys.has(key)) {
         activeFilterKeys.delete(key);
      }
   }
   for (const key of seenIdentityKeys) {
      if (!currentKeys.has(key)) {
         seenIdentityKeys.delete(key);
      }
   }

   const isFirstLoad = seenIdentityKeys.size === 0;

   filterList.replaceChildren();

   for (const id of identities) {
      if (isFirstLoad || !seenIdentityKeys.has(id.key)) {
         activeFilterKeys.add(id.key);
         seenIdentityKeys.add(id.key);
      }

      const item = document.createElement('div');
      item.className = 'filter-item';

      const avatar = document.createElement('img');
      avatar.className = 'filter-item-avatar';
      avatar.src = graffiti.avatarUrl(id.key);
      avatar.alt = id.name;

      const nameSpan = document.createElement('span');
      nameSpan.className = 'filter-item-name';
      nameSpan.textContent = id.name;

      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.className = 'filter-item-checkbox';
      checkbox.checked = activeFilterKeys.has(id.key);

      checkbox.addEventListener('change', () => {
         if (checkbox.checked) {
            activeFilterKeys.add(id.key);
         } else {
            activeFilterKeys.delete(id.key);
         }
         void refreshMessages();
      });

      item.appendChild(avatar);
      item.appendChild(nameSpan);
      item.appendChild(checkbox);

      item.addEventListener('click', (e) => {
         if (e.target !== checkbox) {
            checkbox.checked = !checkbox.checked;
            checkbox.dispatchEvent(new Event('change'));
         }
      });

      filterList.appendChild(item);
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

async function refreshMessages(): Promise<void> {
   await refreshNameMaps();
   const { messages } = await graffiti.listMessages();
   const container = document.getElementById('messages');
   if (!container) return;

   // Filter the messages
   let filteredMessages = messages;
   if (activeFilterKeys.size > 0) {
      filteredMessages = messages.filter(msg => {
         const authorKey = msg.authorKey || nameToKey.get(msg.author || '');
         const recipientKey = msg.recipientKey || nameToKey.get(msg.recipient || '');

         // 1. Recipient key matches active filter key
         if (recipientKey && activeFilterKeys.has(recipientKey)) {
            return true;
         }
         // 2. Author key matches peerKey of active filter identity
         if (authorKey) {
            const matchingId = knownIdentities.find(id => id.peerKey === authorKey);
            if (matchingId && activeFilterKeys.has(matchingId.key)) {
               return true;
            }
         }
         return false;
      });
   } else {
      filteredMessages = [];
   }

   const targetKeys = new Set(filteredMessages.map(m => m.key));

   // 1. Remove elements that are no longer present
   const children = Array.from(container.children) as HTMLElement[];
   for (const child of children) {
      const key = child.dataset.msgKey;
      if (key && !targetKeys.has(key)) {
         child.remove();
         currentMessages.delete(key);
      }
   }

   // 2. Insert or move elements to align with the backend's sorted list
   for (let i = 0; i < filteredMessages.length; i++) {
      const msg = filteredMessages[i];
      const existingEl = container.children[i] as HTMLElement | undefined;

      if (existingEl && existingEl.dataset.msgKey === msg.key) {
         // Already in correct position — update headers in case name changed
         fillHeader(existingEl, msg);
         continue;
      }

      // See if it exists elsewhere in the DOM
      const foundEl = container.querySelector(`[data-msg-key="${CSS.escape(msg.key)}"]`) as HTMLElement | null;

      if (foundEl) {
         // Move it to index i
         container.insertBefore(foundEl, existingEl ?? null);
      } else {
         // Create new element and insert it at index i
         const newEl = createMessageElement(msg);
         if (newEl) {
            container.insertBefore(newEl, existingEl ?? null);
         }
      }
   }

   // 3. Keep currentMessages in sync
   currentMessages.clear();
   for (const msg of filteredMessages) {
      currentMessages.add(msg.key);
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
   item.querySelector<HTMLButtonElement>('.msg-btn-export')?.addEventListener('click', () => {
      graffiti.exportMessage(msg.key)
         .catch((err: Error) => setStatus(`Export failed: ${err.message}`));
   });
   item.querySelector<HTMLButtonElement>('.msg-btn-delete')?.addEventListener('click', () => {
      graffiti.removeMessage(msg.key)
         .then(() => {
            currentMessages.delete(msg.key);
            item.remove();
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
         fetch(url)
            .then(r => r.text())
            .then(t => {
               pre.textContent = t;
            })
            .catch((err: Error) => {
               pre.textContent = `[Error loading content: ${err.message}]`;
            });
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
   }

   return el;
}

function displayMessage(msg: MessageData): void {
   const el = createMessageElement(msg);
   if (el) {
      document.getElementById('messages')?.append(el);
   }
}

async function populateSelects(): Promise<void> {
   const [{ identities }, { peers }, node] = await Promise.all([
      graffiti.listIdentities(),
      graffiti.listPeers(),
      graffiti.nodeInfo(),
   ]);
   knownIdentities = identities;

   nameToKey.clear();
   for (const id of identities) {
      nameToKey.set(id.name, id.key);
   }
   for (const peer of peers) {
      nameToKey.set(peer.name, peer.key);
   }

   const prevFrom = fromField?.value ?? '';
   const prevTo = toField?.value ?? '';

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
         }
      }
   }
   updateSameAuthorRecipientWarning();
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

fromField?.addEventListener('change', updateSameAuthorRecipientWarning);
toField?.addEventListener('change', updateSameAuthorRecipientWarning);

refreshBtn?.addEventListener('click', () => {
   void refreshMessages()
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

// ── Sidebar / Drawer Logic ───────────────────────────────────────────────────
const filterToggleBtn = document.getElementById('btn-filter-toggle');
const sidebarCloseBtn = document.getElementById('btn-sidebar-close');
const sidebar = document.getElementById('identities-sidebar');
const backdrop = document.getElementById('sidebar-backdrop');
const filterAllBtn = document.getElementById('btn-filter-all');
const filterNoneBtn = document.getElementById('btn-filter-none');

function openSidebar(): void {
   sidebar?.classList.add('is-open');
   backdrop?.classList.add('is-visible');
}

function closeSidebar(): void {
   sidebar?.classList.remove('is-open');
   backdrop?.classList.remove('is-visible');
}

filterToggleBtn?.addEventListener('click', openSidebar);
sidebarCloseBtn?.addEventListener('click', closeSidebar);
backdrop?.addEventListener('click', closeSidebar);

filterAllBtn?.addEventListener('click', () => {
   const checkboxes = document.querySelectorAll<HTMLInputElement>('.filter-item-checkbox');
   checkboxes.forEach(cb => {
      cb.checked = true;
   });
   for (const id of knownIdentities) {
      activeFilterKeys.add(id.key);
   }
   void refreshMessages();
});

filterNoneBtn?.addEventListener('click', () => {
   const checkboxes = document.querySelectorAll<HTMLInputElement>('.filter-item-checkbox');
   checkboxes.forEach(cb => {
      cb.checked = false;
   });
   activeFilterKeys.clear();
   void refreshMessages();
});

// ── Bootstrap ─────────────────────────────────────────────────────────────────
setStatus('Ready');
autoResizeTextarea(messageText);
onSectionShow('section-messages', () => {
   void populateSelects();
   void populateIdentityFilters();
});

refreshMessages().catch(e => console.error('[messages] bootstrap error:', e));

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
      currentMessages.delete(msg.key as string);
      document.querySelector(`[data-msg-key="${CSS.escape(msg.key as string)}"]`)?.remove();
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
   void populateIdentityFilters();
});
onWsEvent('messages_reload', queueRefreshMessages);
onWsEvent('peers_update', populateSelects);

