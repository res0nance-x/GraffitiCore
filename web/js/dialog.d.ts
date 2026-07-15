/**
 * Reusable modal dialog utility — ES module (TypeScript).
 */
export interface DialogOptions {
    title: string;
    templateId: string;
    confirmLabel?: string;
    init?: (body: HTMLElement) => void;
}
/**
 * Show a modal dialog built from an HTML <template>.
 * Returns a plain object of { inputName: value } or null if cancelled.
 */
export declare function showDialog({ title, templateId, confirmLabel, init }: DialogOptions): Promise<Record<string, string> | null>;
