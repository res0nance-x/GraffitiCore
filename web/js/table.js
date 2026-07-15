import { graffiti } from './graffiti-api.js';
/**
 * Builds (or rebuilds) the <tbody> of a data table.
 */
export function buildTable(table, items, { onRemove, onExport, nodeKey } = {}) {
    const tbody = table.tBodies[0] ?? table.createTBody();
    tbody.replaceChildren();
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
        if (nodeKey && item.key === nodeKey) {
            const badge = document.createElement('span');
            badge.className = 'badge-relay';
            badge.style.background = '#2d2dff';
            badge.style.cursor = 'default';
            badge.textContent = 'Node';
            figcaption.appendChild(badge);
        }
        figure.append(img, figcaption);
        userCell.append(figure);
        // Actions
        const actionsCell = row.insertCell();
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
//# sourceMappingURL=table.js.map