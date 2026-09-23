import {graffiti, IdentityEntry, PeerEntry} from './graffiti-api.js';
import {buildTable, TableItem} from './table.js';
import {showDialog} from './dialog.js';
import {onSectionShow, onWsEvent, onWsOpen} from './app.js';

const identitiesTable = document.getElementById('identities') as HTMLTableElement;
const peersTable = document.getElementById('peers') as HTMLTableElement;

export async function refreshIdentities(): Promise<void> {
   if (!identitiesTable) return;
   const [{identities}, node] = await Promise.all([
      graffiti.listIdentities(),
      graffiti.nodeInfo(),
   ]);
   buildTable(identitiesTable, identities, {
      nodeKey: node.peerKey,
      onAddToPeers: async (item: IdentityEntry) => {
         try {
            await graffiti.identityToPeer(item.key);
            await refreshPeers();
         } catch (err) {
            alert(`Add to peers failed: ${(err as Error).message}`);
         }
      },
      onTogglePersist: async (item: IdentityEntry) => {
         try {
            await graffiti.setIdentityPersistence(item.key, !item.persistent);
            await refreshIdentities();
         } catch (err) {
            alert(`Persistence update failed: ${(err as Error).message}`);
         }
      },
      onRemove: async (item: TableItem) => {
         const iden = item as IdentityEntry;
         if (iden.key === node.peerKey) {
            alert("Cannot remove the server node identity.");
            return;
         }
         if (!confirm(`Remove identity "${iden.name}"? This cannot be undone.`)) return;
         try {
            await graffiti.removeIdentity(iden.key);
            await refreshIdentities();
         } catch (err) {
            alert(`Remove failed: ${(err as Error).message}`);
         }
      },
   });
}

export async function refreshPeers(): Promise<void> {
   if (!peersTable) return;
   const {peers} = await graffiti.listPeers();
   buildTable(peersTable, peers, {
      onRemove: async (item: PeerEntry) => {
         if (!confirm(`Remove peer "${item.name}"?`)) return;
         try {
            await graffiti.removePeer(item.key);
            await refreshPeers();
         } catch (err) {
            alert(`Remove failed: ${(err as Error).message}`);
         }
      },
      onExport: (item: PeerEntry) => graffiti.exportPeer(item.key),
   });
}

export async function refresh(): Promise<void> {
   await Promise.all([refreshIdentities(), refreshPeers(), refreshWhitelist()]);
}

// ── Create Identity ───────────────────────────────────────────────────────────

document.getElementById('create-identity')?.addEventListener('click', async () => {
   const data = await showDialog({
      title: 'Create Identity',
      templateId: 'tpl-identity-create',
      confirmLabel: 'Create',
   });
   if (!data) return;
   const rawSeed = data.seed || '';
   const splitByComma = data.splitByComma === 'on';
   const saveOnDevice = data.saveOnDevice === 'on';
   const seeds = splitByComma
      ? rawSeed.split(',').map((s: string) => s.trim()).filter((s: string) => s.length > 0)
      : [rawSeed.trim()].filter(s => s.length > 0);
   if (seeds.length === 0) {
      alert('A seed phrase is required.');
      return;
   }
   const errors: string[] = [];
   for (const seed of seeds) {
      try {
         const res = await graffiti.createIdentity(seed);
         if (saveOnDevice && res?.key) {
            await graffiti.setIdentityPersistence(res.key, true);
         }
      } catch (err) {
         errors.push(`"${seed}": ${(err as Error).message}`);
      }
   }
   await refreshIdentities();
   if (errors.length > 0) {
      alert(`Some identities failed to create:\n${errors.join('\n')}`);
   }
});

// ── Import Peer ───────────────────────────────────────────────────────────────

document.getElementById('import-peer')?.addEventListener('click', () => {
   (document.getElementById('import-peer-file') as HTMLInputElement)?.click();
});

document.getElementById('import-peer-file')?.addEventListener('change', async (e: Event) => {
   const input = e.target as HTMLInputElement;
   const file = input.files?.[0];
   if (!file) return;
   try {
      await graffiti.importPeer(file);
      await refreshPeers();
   } catch (err) {
      alert(`Import failed: ${(err as Error).message}`);
   } finally {
      input.value = '';
   }
});

// ── Whitelist Toggle ─────────────────────────────────────────────────────────

const whitelistToggle = document.getElementById('whitelist-toggle') as HTMLInputElement | null;

export async function refreshWhitelist(): Promise<void> {
   if (!whitelistToggle) return;
   try {
      const res = await graffiti.getWhitelist();
      whitelistToggle.checked = res.enabled;
   } catch (_: unknown) {}
}

whitelistToggle?.addEventListener('change', async () => {
   try {
      await graffiti.setWhitelist(whitelistToggle.checked);
   } catch (err) {
      alert(`Failed to update whitelist: ${(err as Error).message}`);
      await refreshWhitelist();
   }
});

onSectionShow('section-identity', refresh);
onWsEvent('identities_update', refreshIdentities);
onWsEvent('peers_update', refreshPeers);
onWsEvent('whitelist_update', (data: { enabled?: boolean }) => {
   if (whitelistToggle && typeof data?.enabled === 'boolean') {
      whitelistToggle.checked = data.enabled;
   } else {
      refreshWhitelist();
   }
});
onWsOpen(refresh);
