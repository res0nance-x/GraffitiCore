import { showSection, onSectionShow } from './app.js';
import { graffiti } from './graffiti-api.js';
// ── Help Documentation Navigation ─────────────────────────────────────────────
const showHelpBtn = document.getElementById('btn-show-help');
const closeHelpBtn = document.getElementById('btn-close-help');
if (showHelpBtn) {
    showHelpBtn.addEventListener('click', () => {
        showSection('section-help');
    });
}
if (closeHelpBtn) {
    closeHelpBtn.addEventListener('click', () => {
        showSection('section-settings');
    });
}
// ── Message Appearance Settings ───────────────────────────────────────────────
const fontSizeSel = document.getElementById('settings-font-size');
const fontFamilySel = document.getElementById('settings-font-family');
const messagesEl = document.getElementById('messages');
function applyMessageAppearance(size, family) {
    document.documentElement.style.setProperty('--app-font-size', size);
    if (messagesEl) {
        messagesEl.style.setProperty('--message-font-family', family);
    }
    if (fontSizeSel)
        fontSizeSel.value = size;
    if (fontFamilySel)
        fontFamilySel.value = family;
}
async function loadMessageAppearance() {
    const size = await graffiti.getStore('graffiti:message-font-size') || '100%';
    const family = await graffiti.getStore('graffiti:message-font-family') || 'inherit';
    applyMessageAppearance(size, family);
}
if (fontSizeSel) {
    fontSizeSel.addEventListener('change', async () => {
        await graffiti.setStore('graffiti:message-font-size', fontSizeSel.value);
        await loadMessageAppearance();
    });
}
if (fontFamilySel) {
    fontFamilySel.addEventListener('change', async () => {
        await graffiti.setStore('graffiti:message-font-family', fontFamilySel.value);
        await loadMessageAppearance();
    });
}
// Apply settings initially
void loadMessageAppearance();
// ── Storage Management ────────────────────────────────────────────────────────
function formatSize(bytes) {
    if (bytes == null)
        return '';
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1024 * 1024)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
function escHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
async function loadStorageInfo() {
    try {
        const res = await graffiti.getStorage();
        const overallEl = document.getElementById('storage-total-overall');
        if (overallEl)
            overallEl.textContent = formatSize(res.overall);
        const quotaInput = document.getElementById('settings-storage-quota');
        if (quotaInput) {
            try {
                const quotaRes = await graffiti.getQuota();
                quotaInput.value = quotaRes.quota > 0 ? String(Math.round(quotaRes.quota / (1024 * 1024))) : '';
            }
            catch (e) {
                console.error('Failed to load quota:', e);
            }
        }
        const listEl = document.getElementById('storage-list');
        if (!listEl)
            return;
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
            card.querySelector('.btn-purge-half').addEventListener('click', async () => {
                if (!confirm(`Purge oldest half of messages for "${item.name}"?`))
                    return;
                try {
                    await graffiti.purgeStorage(item.key, 'half');
                    await loadStorageInfo();
                }
                catch (e) {
                    alert(`Purge failed: ${e.message}`);
                }
            });
            card.querySelector('.btn-purge-all').addEventListener('click', async () => {
                if (!confirm(`Purge ALL messages for "${item.name}"? This cannot be undone.`))
                    return;
                try {
                    await graffiti.purgeStorage(item.key, 'all');
                    await loadStorageInfo();
                }
                catch (e) {
                    alert(`Purge failed: ${e.message}`);
                }
            });
            listEl.appendChild(card);
        }
    }
    catch (e) {
        console.error('Failed to load storage info:', e);
    }
}
// Reload storage info when entering Settings tab
onSectionShow('section-settings', async () => {
    await loadStorageInfo();
});
const quotaInput = document.getElementById('settings-storage-quota');
if (quotaInput) {
    quotaInput.addEventListener('change', async () => {
        const mb = parseFloat(quotaInput.value);
        const bytes = isNaN(mb) || mb <= 0 ? 0 : Math.round(mb * 1024 * 1024);
        try {
            await graffiti.setQuota(bytes);
        }
        catch (e) {
            alert(`Failed to save quota: ${e.message}`);
        }
    });
}
//# sourceMappingURL=storage.js.map