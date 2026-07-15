import { IdentityEntry, PeerEntry } from './graffiti-api.js';
export type TableItem = IdentityEntry | PeerEntry;
export interface TableOptions {
    onRemove?: (item: TableItem) => void | Promise<void>;
    onExport?: (item: TableItem) => void | Promise<void>;
}
/**
 * Builds (or rebuilds) the <tbody> of a data table.
 */
export declare function buildTable(table: HTMLTableElement, items: TableItem[], { onRemove, onExport }?: TableOptions): void;
