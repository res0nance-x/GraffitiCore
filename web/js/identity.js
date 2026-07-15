import { graffiti } from './graffiti-api.js';
import { buildTable } from './table.js';
import { showDialog } from './dialog.js';
import { onSectionShow, onWsEvent } from './app.js';
const table = document.getElementById('identities');
export async function refresh() {
    const [{ identities }, node] = await Promise.all([
        graffiti.listIdentities(),
        graffiti.nodeInfo(),
    ]);
    buildTable(table, identities, {
        nodeKey: node.peerKey,
        onRemove: async (item) => {
            const iden = item;
            if (iden.key === node.peerKey) {
                alert("Cannot remove the server node identity.");
                return;
            }
            if (!confirm(`Remove identity "${iden.name}"? This cannot be undone.`))
                return;
            try {
                await graffiti.removeIdentity(iden.key);
                await refresh();
            }
            catch (err) {
                alert(`Remove failed: ${err.message}`);
            }
        },
    });
}
// ── Create ────────────────────────────────────────────────────────────────────
document.getElementById('create-identity').addEventListener('click', async () => {
    const data = await showDialog({
        title: 'Create Identity',
        templateId: 'tpl-identity-create',
        confirmLabel: 'Create',
    });
    if (!data)
        return;
    const rawSeed = data.seed || '';
    const splitByComma = data.splitByComma === 'on';
    const seeds = splitByComma
        ? rawSeed.split(',').map((s) => s.trim()).filter((s) => s.length > 0)
        : [rawSeed.trim()].filter(s => s.length > 0);
    if (seeds.length === 0) {
        alert('A seed phrase is required.');
        return;
    }
    const errors = [];
    for (const seed of seeds) {
        try {
            await graffiti.createIdentity(seed);
        }
        catch (err) {
            errors.push(`"${seed}": ${err.message}`);
        }
    }
    await refresh();
    if (errors.length > 0) {
        alert(`Some identities failed to create:\n${errors.join('\n')}`);
    }
});
onSectionShow('section-identity', refresh);
onWsEvent('identities_update', refresh);
//# sourceMappingURL=identity.js.map