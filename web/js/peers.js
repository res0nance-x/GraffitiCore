import { graffiti } from './graffiti-api.js';
import { buildTable } from './table.js';
import { onSectionShow, onWsEvent } from './app.js';
const table = document.getElementById('peers');
export async function refresh() {
    const { peers } = await graffiti.listPeers();
    buildTable(table, peers, {
        onRemove: async (item) => {
            if (!confirm(`Remove peer "${item.name}"?`))
                return;
            try {
                await graffiti.removePeer(item.key);
                await refresh();
            }
            catch (err) {
                alert(`Remove failed: ${err.message}`);
            }
        },
        onExport: (item) => graffiti.exportPeer(item.key),
    });
}
// ── Import ────────────────────────────────────────────────────────────────────
document.getElementById('import-peer').addEventListener('click', () => {
    document.getElementById('import-peer-file').click();
});
document.getElementById('import-peer-file').addEventListener('change', async (e) => {
    const input = e.target;
    const file = input.files?.[0];
    if (!file)
        return;
    try {
        await graffiti.importPeer(file);
        await refresh();
    }
    catch (err) {
        alert(`Import failed: ${err.message}`);
    }
    finally {
        input.value = '';
    }
});
onSectionShow('section-peers', refresh);
onWsEvent('peers_update', refresh);
//# sourceMappingURL=peers.js.map