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

