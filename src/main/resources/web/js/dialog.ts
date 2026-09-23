/**
 * Reusable modal dialog utility — ES module (TypeScript).
 */

export interface DialogOptions {
   title: string;
   templateId: string;
   confirmLabel?: string;
   init?: (body: HTMLElement) => void;
}

/** The lazily-created <dialog> element. */
let dialog: HTMLDialogElement | null = null;

function ensureDialog(): void {
   if (dialog) return;

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

   dialog.addEventListener('click', (e: MouseEvent) => {
      if (e.target === dialog) dialog!.close('cancel');
   });

   dialog.querySelector<HTMLButtonElement>('.app-dialog-cancel')!.addEventListener('click', () => {
      dialog!.close('cancel');
   });
}

/**
 * Show a modal dialog built from an HTML <template>.
 * Returns a plain object of { inputName: value } or null if cancelled.
 */
export function showDialog({
                              title,
                              templateId,
                              confirmLabel = 'OK',
                              init
                           }: DialogOptions): Promise<Record<string, string> | null> {
   ensureDialog();

   const tpl = document.getElementById(templateId) as HTMLTemplateElement | null;
   if (!tpl) throw new Error(`showDialog: <template id="${templateId}"> not found`);

   dialog!.querySelector<HTMLElement>('.app-dialog-title')!.textContent = title;
   dialog!.querySelector<HTMLButtonElement>('.app-dialog-confirm')!.textContent = confirmLabel;

   const cancelBtn = dialog!.querySelector<HTMLButtonElement>('.app-dialog-cancel');
   if (cancelBtn) cancelBtn.style.display = '';

   const body = dialog!.querySelector<HTMLElement>('.app-dialog-body')!;
   body.replaceChildren(tpl.content.cloneNode(true));

   if (init) init(body);

   dialog!.returnValue = '';
   dialog!.showModal();

   const first = body.querySelector<HTMLElement>('input, textarea, select');
   if (first) requestAnimationFrame(() => first.focus());

   return new Promise<Record<string, string> | null>((resolve) => {
      function onClose() {
         dialog!.removeEventListener('close', onClose);
         if (dialog!.returnValue === 'confirm') {
            const form = dialog!.querySelector<HTMLFormElement>('form')!;
            resolve(Object.fromEntries(new FormData(form)) as Record<string, string>);
         } else {
            resolve(null);
         }
      }

      dialog!.addEventListener('close', onClose);
   });
}

export interface ProgressDialog {
   update(statusText: string, percent: number, detailText?: string): void;
   close(): void;
}

let progressDialog: HTMLDialogElement | null = null;

function ensureProgressDialog(): HTMLDialogElement {
   if (progressDialog) return progressDialog;

   progressDialog = document.createElement('dialog');
   progressDialog.className = 'app-dialog progress-modal';
   progressDialog.innerHTML = `
        <div class="app-dialog-form">
            <h2 class="app-dialog-title" id="progress-dialog-title">Creating Pack Archive</h2>
            <div class="app-dialog-body">
                <div class="dialog-field" style="text-align: center; padding: 0.5rem 0;">
                    <div id="progress-dialog-status" style="font-weight: 600; margin-bottom: 0.75rem;">Preparing...</div>
                    <div style="background: rgba(255,255,255,0.12); border-radius: 6px; overflow: hidden; height: 12px; margin-bottom: 0.75rem;">
                        <div id="progress-dialog-bar" style="width: 0%; height: 100%; background: var(--accent, #38bdf8); transition: width 0.15s ease;"></div>
                    </div>
                    <div id="progress-dialog-detail" style="font-size: 0.85rem; opacity: 0.7; word-break: break-all;"></div>
                </div>
            </div>
        </div>`;
   document.body.appendChild(progressDialog);

   // Prevent closing on escape while in progress
   progressDialog.addEventListener('cancel', (e) => e.preventDefault());
   return progressDialog;
}

export function showProgressModal(title: string, initialStatus = 'Preparing...'): ProgressDialog {
   const pd = ensureProgressDialog();
   const titleEl = pd.querySelector<HTMLElement>('#progress-dialog-title');
   const statusEl = pd.querySelector<HTMLElement>('#progress-dialog-status');
   const barEl = pd.querySelector<HTMLElement>('#progress-dialog-bar');
   const detailEl = pd.querySelector<HTMLElement>('#progress-dialog-detail');

   if (titleEl) titleEl.textContent = title;
   if (statusEl) statusEl.textContent = initialStatus;
   if (barEl) barEl.style.width = '0%';
   if (detailEl) detailEl.textContent = '';

   if (!pd.open) {
      pd.showModal();
   }

   return {
      update(statusText: string, percent: number, detailText?: string) {
         if (statusEl) statusEl.textContent = statusText;
         if (barEl) barEl.style.width = `${Math.max(0, Math.min(100, percent))}%`;
         if (detailEl) detailEl.textContent = detailText ?? '';
      },
      close() {
         if (pd.open) {
            pd.close();
         }
      }
   };
}


