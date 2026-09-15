import { showSection, onSectionShow } from './app.js';
import { graffiti } from './graffiti-api.js';

// ── Help Documentation Navigation & Dynamic Loading ─────────────────────────
const showHelpBtn = document.getElementById('btn-show-help') as HTMLButtonElement | null;
const closeHelpBtn = document.getElementById('btn-close-help') as HTMLButtonElement | null;
const helpContentEl = document.getElementById('help-content') as HTMLElement | null;

export function parseHelpHtmlSections(htmlText: string): string {
   const parser = new DOMParser();
   const doc = parser.parseFromString(htmlText, 'text/html');
   const body = doc.body;
   if (!body || !body.children.length) {
      return '';
   }

   // Ensure all links open safely in a new window/tab
   doc.querySelectorAll('a[href]').forEach(a => {
      a.setAttribute('target', '_blank');
      a.setAttribute('rel', 'noopener');
   });

   // Ensure images have the msg-img class for consistent styling
   doc.querySelectorAll('img').forEach(img => {
      img.classList.add('msg-img');
   });

   const cards: string[] = [];
   let currentCardNodes: string[] = [];

   for (let i = 0; i < body.children.length; i++) {
      const child = body.children[i];
      const isHeader = /^H[1-3]$/i.test(child.tagName);

      if (isHeader && currentCardNodes.length > 0) {
         cards.push(`<section class="net-card">\n${currentCardNodes.join('\n')}\n</section>`);
         currentCardNodes = [];
      }
      currentCardNodes.push(child.outerHTML);
   }

   if (currentCardNodes.length > 0) {
      cards.push(`<section class="net-card">\n${currentCardNodes.join('\n')}\n</section>`);
   }

   return cards.join('\n\n');
}

export async function loadHelpDocumentation(): Promise<void> {
   if (!helpContentEl) return;
   try {
      const resp = await fetch('help.html?t=' + Date.now());
      if (!resp.ok) {
         throw new Error(`HTTP ${resp.status}`);
      }
      const text = await resp.text();
      helpContentEl.innerHTML = parseHelpHtmlSections(text);
   } catch (err) {
      console.error('Failed to load help.html:', err);
      helpContentEl.innerHTML = `<section class="net-card"><p style="color:var(--danger, #f44336); font-size:0.9rem;">Failed to load help documentation.</p></section>`;
   }
}

if (showHelpBtn) {
   showHelpBtn.addEventListener('click', () => {
      loadHelpDocumentation();
      showSection('section-help');
   });
}

if (closeHelpBtn) {
   closeHelpBtn.addEventListener('click', () => {
      showSection('section-settings');
   });
}

onSectionShow('section-help', () => {
   loadHelpDocumentation();
});

// ── Appearance Settings ───────────────────────────────────────────────────────
const themeSel = document.getElementById('settings-theme') as HTMLSelectElement | null;
const fontSizeSel = document.getElementById('settings-font-size') as HTMLSelectElement | null;
const fontFamilySel = document.getElementById('settings-font-family') as HTMLSelectElement | null;
const messagesEl = document.getElementById('messages') as HTMLElement | null;

function applyTheme(theme: string): void {
   if (!theme) theme = 'dark-sage';
   if (theme === 'dark') theme = 'dark-sage';
   if (theme === 'light') theme = 'light-purple';

   document.documentElement.setAttribute('data-theme', theme);
   if (themeSel) themeSel.value = theme;

   const metaTheme = document.querySelector('meta[name="theme-color"]');
   if (metaTheme) {
      metaTheme.setAttribute('content', theme.startsWith('light') ? '#ffffff' : '#000000');
   }
}

function applyMessageAppearance(size: string, family: string): void {
   document.documentElement.style.setProperty('--app-font-size', size);
   if (messagesEl) {
      messagesEl.style.setProperty('--message-font-family', family);
   }
   if (fontSizeSel) fontSizeSel.value = size;
   if (fontFamilySel) fontFamilySel.value = family;
}

async function loadAppearanceSettings(): Promise<void> {
   try {
      const storedTheme = await graffiti.getStore('graffiti:theme');
      applyTheme(storedTheme || 'dark-sage');
   } catch {
      applyTheme('dark-sage');
   }

   const size = await graffiti.getStore('graffiti:message-font-size') || '100%';
   const family = await graffiti.getStore('graffiti:message-font-family') || 'inherit';
   applyMessageAppearance(size, family);
}

if (themeSel) {
   themeSel.addEventListener('change', async () => {
      const selectedTheme = themeSel.value;
      applyTheme(selectedTheme);
      await graffiti.setStore('graffiti:theme', selectedTheme);
   });
}

if (fontSizeSel) {
   fontSizeSel.addEventListener('change', async () => {
      await graffiti.setStore('graffiti:message-font-size', fontSizeSel.value);
      const family = fontFamilySel?.value || 'inherit';
      applyMessageAppearance(fontSizeSel.value, family);
   });
}

if (fontFamilySel) {
   fontFamilySel.addEventListener('change', async () => {
      await graffiti.setStore('graffiti:message-font-family', fontFamilySel.value);
      const size = fontSizeSel?.value || '100%';
      applyMessageAppearance(size, fontFamilySel.value);
   });
}

// Apply settings initially
void loadAppearanceSettings();

// ── Notification Settings ───────────────────────────────────────────────────
const bellSoundSel = document.getElementById('settings-bell-sound') as HTMLSelectElement | null;
const previewBellBtn = document.getElementById('btn-preview-bell') as HTMLButtonElement | null;
const ignoreUrgentCheck = document.getElementById('settings-ignore-urgent') as HTMLInputElement | null;

async function loadNotificationSettings(): Promise<void> {
   if (bellSoundSel) {
      try {
         const stored = await graffiti.getStore('graffiti:bell-sound');
         bellSoundSel.value = stored || 'chime';
      } catch {
         bellSoundSel.value = 'chime';
      }
   }
   if (ignoreUrgentCheck) {
      try {
         const stored = await graffiti.getStore('graffiti:ignore-urgent');
         ignoreUrgentCheck.checked = stored === 'true';
      } catch {
         ignoreUrgentCheck.checked = false;
      }
   }
}

if (bellSoundSel) {
   bellSoundSel.addEventListener('change', async () => {
      await graffiti.setStore('graffiti:bell-sound', bellSoundSel.value);
   });
}

if (ignoreUrgentCheck) {
   ignoreUrgentCheck.addEventListener('change', async () => {
      await graffiti.setStore('graffiti:ignore-urgent', ignoreUrgentCheck.checked ? 'true' : 'false');
   });
}

if (previewBellBtn && bellSoundSel) {
   previewBellBtn.addEventListener('click', () => {
      const sound = bellSoundSel.value;
      if (sound === 'mute') return;
      const audio = new Audio(`/sounds/${sound}.wav`);
      audio.play().catch(e => console.warn('Preview sound playback error:', e));
   });
}

void loadNotificationSettings();

// ── Storage Management ────────────────────────────────────────────────────────
function formatSize(bytes: number | null | undefined): string {
   if (bytes == null) return '';
   if (bytes < 1024) return `${bytes} B`;
   if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
   return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function escHtml(str: string): string {
   return String(str)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

async function loadStorageInfo(): Promise<void> {
   try {
      const res = await graffiti.getStorage();
      const overallEl = document.getElementById('storage-total-overall');
      if (overallEl) overallEl.textContent = formatSize(res.overall);

      const quotaInput = document.getElementById('settings-storage-quota') as HTMLInputElement | null;
      if (quotaInput) {
         try {
            const quotaRes = await graffiti.getQuota();
            quotaInput.value = quotaRes.quota > 0 ? String(Math.round(quotaRes.quota / (1024 * 1024))) : '';
         } catch (e) {
            console.error('Failed to load quota:', e);
         }
      }

      const listEl = document.getElementById('storage-list');
      if (!listEl) return;
      listEl.replaceChildren();

      if (res.storage.length === 0) {
         const empty = document.createElement('div');
         empty.className = 'empty-row';
         empty.textContent = 'No storage statistics available.';
         listEl.appendChild(empty);
         return;
      }

      for (const item of res.storage) {
         const card = document.createElement('div');
         card.className = 'net-item-card';
         card.innerHTML = `
            <div class="net-item-header">
               <div class="net-item-user">
                  <img class="msg-avatar" src="${graffiti.avatarUrl(item.key)}" width="32" height="32" alt="">
                  <span class="net-item-name">${escHtml(item.name)}</span>
               </div>
            </div>
            <div class="net-item-details">
               <div class="net-detail-row">
                  <span class="detail-label">Storage Used:</span>
                  <span>${formatSize(item.size)}</span>
               </div>
            </div>
            <div class="net-item-actions">
               <button class="btn-purge-half" type="button">Purge 1/2</button>
               <button class="btn-purge-all" style="color: red; border-color: red;" type="button">Purge All</button>
            </div>
         `;

         card.querySelector<HTMLButtonElement>('.btn-purge-half')!.addEventListener('click', async () => {
            if (!confirm(`Purge oldest half of messages for "${item.name}"?`)) return;
            try {
               await graffiti.purgeStorage(item.key, 'half');
               await loadStorageInfo();
            } catch (e) {
               alert(`Purge failed: ${(e as Error).message}`);
            }
         });

         card.querySelector<HTMLButtonElement>('.btn-purge-all')!.addEventListener('click', async () => {
            if (!confirm(`Purge ALL messages for "${item.name}"? This cannot be undone.`)) return;
            try {
               await graffiti.purgeStorage(item.key, 'all');
               await loadStorageInfo();
            } catch (e) {
               alert(`Purge failed: ${(e as Error).message}`);
            }
         });

         listEl.appendChild(card);
      }
   } catch (e) {
      console.error('Failed to load storage info:', e);
   }
}

// ── About / Version ───────────────────────────────────────────────────────────
const appVersionEl = document.getElementById('settings-app-version') as HTMLElement | null;

async function loadAppVersion(): Promise<void> {
   if (!appVersionEl) return;
   try {
      const ver = await graffiti.getVersion();
      appVersionEl.textContent = ver;
   } catch {
      appVersionEl.textContent = 'Unknown';
   }
}

void loadAppVersion();

// Reload storage info and version when entering Settings tab
onSectionShow('section-settings', async () => {
   await Promise.all([loadStorageInfo(), loadAppVersion()]);
});

const quotaInput = document.getElementById('settings-storage-quota') as HTMLInputElement | null;
if (quotaInput) {
   quotaInput.addEventListener('change', async () => {
      const mb = parseFloat(quotaInput.value);
      const bytes = isNaN(mb) || mb <= 0 ? 0 : Math.round(mb * 1024 * 1024);
      try {
         await graffiti.setQuota(bytes);
      } catch (e) {
         alert(`Failed to save quota: ${(e as Error).message}`);
      }
   });
}
