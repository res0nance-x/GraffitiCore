import {graffiti, PeerEntry} from './graffiti-api.js';
import {buildTable} from './table.js';
import {onSectionShow, onWsEvent} from './app.js';

const table = document.getElementById('peers') as HTMLTableElement;

export async function refresh(): Promise<void> {
   const {peers} = await graffiti.listPeers();
   buildTable(table, peers, {
      onRemove: async (item: PeerEntry) => {
         if (!confirm(`Remove peer "${item.name}"?`)) return;
         try {
            await graffiti.removePeer(item.key);
            await refresh();
         } catch (err) {
            alert(`Remove failed: ${(err as Error).message}`);
         }
      },
      onExport: (item: PeerEntry) => graffiti.exportPeer(item.key),
   });
}

// ── Import ────────────────────────────────────────────────────────────────────

document.getElementById('import-peer')!.addEventListener('click', () => {
   (document.getElementById('import-peer-file') as HTMLInputElement).click();
});

document.getElementById('import-peer-file')!.addEventListener('change', async (e: Event) => {
   const input = e.target as HTMLInputElement;
   const file = input.files?.[0];
   if (!file) return;
   try {
      await graffiti.importPeer(file);
      await refresh();
   } catch (err) {
      alert(`Import failed: ${(err as Error).message}`);
   } finally {
      input.value = '';
   }
});

onSectionShow('section-peers', refresh);
onWsEvent('peers_update', refresh);

