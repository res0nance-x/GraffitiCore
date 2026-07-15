/**
 * Reusable modal dialog utility — ES module (TypeScript).
 */
/** The lazily-created <dialog> element. */
let dialog = null;
function ensureDialog() {
    if (dialog)
        return;
    dialog = document.createElement('dialog');
    dialog.className = 'app-dialog';
    dialog.innerHTML = `
        <form method="dialog" class="app-dialog-form">
            <h2 class="app-dialog-title"></h2>
            <div class="app-dialog-body"></div>
            <div class="app-dialog-actions">
                <button type="button"   class="app-dialog-cancel">Cancel</button>
                <button type="submit"   class="app-dialog-confirm" value="confirm">OK</button>
            </div>
        </form>`;
    document.body.appendChild(dialog);
    dialog.addEventListener('click', (e) => {
        if (e.target === dialog)
            dialog.close('cancel');
    });
    dialog.querySelector('.app-dialog-cancel').addEventListener('click', () => {
        dialog.close('cancel');
    });
}
/**
 * Show a modal dialog built from an HTML <template>.
 * Returns a plain object of { inputName: value } or null if cancelled.
 */
export function showDialog({ title, templateId, confirmLabel = 'OK', init }) {
    ensureDialog();
    const tpl = document.getElementById(templateId);
    if (!tpl)
        throw new Error(`showDialog: <template id="${templateId}"> not found`);
    dialog.querySelector('.app-dialog-title').textContent = title;
    dialog.querySelector('.app-dialog-confirm').textContent = confirmLabel;
    const body = dialog.querySelector('.app-dialog-body');
    body.replaceChildren(tpl.content.cloneNode(true));
    if (init)
        init(body);
    dialog.returnValue = '';
    dialog.showModal();
    const first = body.querySelector('input, textarea, select');
    if (first)
        requestAnimationFrame(() => first.focus());
    return new Promise((resolve) => {
        function onClose() {
            dialog.removeEventListener('close', onClose);
            if (dialog.returnValue === 'confirm') {
                const form = dialog.querySelector('form');
                resolve(Object.fromEntries(new FormData(form)));
            }
            else {
                resolve(null);
            }
        }
        dialog.addEventListener('close', onClose);
    });
}
//# sourceMappingURL=dialog.js.map