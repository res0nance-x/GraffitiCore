import {graffiti, IdentityEntry, PeerEntry} from './graffiti-api.js';

export type TableItem = IdentityEntry | PeerEntry;

export interface TableOptions {
   onRemove?: (item: TableItem) => void | Promise<void>;
   onExport?: (item: TableItem) => void | Promise<void>;
   onTogglePersist?: (item: IdentityEntry) => void | Promise<void>;
   nodeKey?: string;
}

/**
 * Builds (or rebuilds) the <tbody> of a data table.
 */
export function buildTable(
   table: HTMLTableElement,
   items: TableItem[],
   {onRemove, onExport, onTogglePersist, nodeKey}: TableOptions = {},
): void {
   const tbody = table.tBodies[0] ?? table.createTBody();
   tbody.replaceChildren();

   const hasStorageColumn = (table.tHead?.rows[0]?.cells.length ?? 2) >= 3;

   for (const item of items) {
      const row = tbody.insertRow();

      // User (Avatar + Name in figure/figcaption)
      const userCell = row.insertCell();
      const figure = document.createElement('figure');
      figure.className = 'user-profile-figure';

      const img = document.createElement('img');
      img.src = graffiti.avatarUrl(item.key);
      img.width = 32;
      img.height = 32;
      img.alt = `Avatar for ${item.name}`;

      const figcaption = document.createElement('figcaption');
      figcaption.textContent = item.name;

      if (!hasStorageColumn) {
         if (nodeKey && item.key === nodeKey) {
            const badge = document.createElement('span');
            badge.className = 'badge-relay';
            badge.style.background = '#2d2dff';
            badge.style.cursor = 'default';
            badge.textContent = 'Node';
            figcaption.appendChild(badge);
         } else if ('persistent' in item) {
            const isPersistent = (item as IdentityEntry).persistent;
            const badge = document.createElement('span');
            badge.className = isPersistent ? 'badge-relay' : 'badge-session';
            badge.textContent = isPersistent ? 'Saved' : 'Session';
            figcaption.appendChild(badge);
         }
      }

      figure.append(img, figcaption);
      userCell.append(figure);

      // Storage Column (if table header defines 3 columns)
      if (hasStorageColumn) {
         const storageCell = row.insertCell();
         if (nodeKey && item.key === nodeKey) {
            const badge = document.createElement('span');
            badge.className = 'badge-relay';
            badge.style.background = '#2d2dff';
            badge.style.cursor = 'default';
            badge.textContent = 'Node';
            storageCell.appendChild(badge);
         } else if ('persistent' in item) {
            const isPersistent = (item as IdentityEntry).persistent;
            const badge = document.createElement('span');
            badge.className = isPersistent ? 'badge-relay' : 'badge-session';
            badge.textContent = isPersistent ? 'Saved' : 'Session';
            storageCell.appendChild(badge);
         } else {
            storageCell.textContent = '—';
         }
      }

      // Actions
      const actionsCell = row.insertCell();

      if (onTogglePersist && 'persistent' in item && item.key !== nodeKey) {
         const persistBtn = document.createElement('button');
         const isPersistent = (item as IdentityEntry).persistent;
         persistBtn.textContent = isPersistent ? 'Make Ephemeral' : 'Save';
         persistBtn.style.marginRight = '8px';
         persistBtn.addEventListener('click', () => onTogglePersist(item as IdentityEntry));
         actionsCell.append(persistBtn);
      }

      if (onRemove && item.key !== nodeKey) {
         const removeBtn = document.createElement('button');
         removeBtn.textContent = 'Remove';
         removeBtn.style.color = 'red';
         removeBtn.style.marginRight = '8px';
         removeBtn.addEventListener('click', () => onRemove(item));
         actionsCell.append(removeBtn);
      }

      if (onExport) {
         const exportBtn = document.createElement('button');
         exportBtn.textContent = 'Export';
         exportBtn.addEventListener('click', () => onExport(item));
         actionsCell.append(exportBtn);
      }
   }
}
