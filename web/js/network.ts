/**
 * Network section — discovery, active connections, server management.
 */

import {onSectionShow, onWsEvent, onWsOpen} from './app.js';
import {ConnectionEntry, graffiti} from './graffiti-api.js';

// ── State ─────────────────────────────────────────────────────────────────────

interface ConnectionData extends ConnectionEntry {
   // already has host, port, inbound, peerKey?, peerName?
}

interface DiscoveredNode {
   key: string;
   ips: string[];
   port: number;
   name: string;
   relay?: boolean;
}

/** Map of nodeKey (host:port) → ConnectionData */
const connections = new Map<string, ConnectionData>();

/** Map of relayKey → DiscoveredNode */
const discovered = new Map<string, DiscoveredNode>();

// ── Helpers ───────────────────────────────────────────────────────────────────

const nodeKey = (host: string, port: number): string => `${host}:${port}`;

function avatarImg(key: string | null | undefined, size = 32): HTMLImageElement {
   const img = document.createElement('img');
   img.src = key ? graffiti.avatarUrl(key) : '';
   img.alt = '';
   img.width = size;
   img.height = size;
   img.style.borderRadius = '4px';
   return img;
}

// ── My Node card ────────────────────────────────────────────────────────

async function loadNodeIdentity(): Promise<void> {
   try {
      const info = await graffiti.nodeInfo();
      updateNodeDisplay(info.peerKey, info.peerName, info.ips || []);
      const portInput = document.getElementById('server-port-input') as HTMLInputElement;
      if (portInput) {
         portInput.value = String(info.defaultP2PPort ?? 0);
      }
   } catch (e) {
      console.error('[network] loadNodeIdentity:', e);
   }
}


function updateNodeDisplay(peerKey: string, peerName: string, ips: string[]): void {
   (document.getElementById('node-avatar') as HTMLImageElement).src = graffiti.avatarUrl(peerKey);
   (document.getElementById('node-name') as HTMLElement).textContent = peerName;
   (document.getElementById('node-key') as HTMLElement).textContent = ips.join(', ');
}

function relayInputs(): [HTMLInputElement, HTMLInputElement] {
   return [
      document.getElementById('relay-mode-off') as HTMLInputElement,
      document.getElementById('relay-mode-on') as HTMLInputElement,
   ];
}

function setRelayUi(relay: boolean): void {
   const [off, on] = relayInputs();
   off.checked = !relay;
   on.checked = relay;
}

function setRelayStatus(text: string, isError = false): void {
   const el = document.getElementById('relay-mode-status') as HTMLElement;
   el.textContent = text;
   el.hidden = false;
   el.style.color = isError ? '#ff6b6b' : '';
}

function clearRelayStatus(): void {
   const el = document.getElementById('relay-mode-status') as HTMLElement;
   el.hidden = true;
   el.textContent = '';
   el.style.color = '';
}

async function loadRelayMode(): Promise<void> {
   try {
      const status = await graffiti.nodeRelayStatus();
      setRelayUi(status.relay);
      clearRelayStatus();
   } catch (e) {
      setRelayStatus(`Failed to load relay mode: ${(e as Error).message}`, true);
   }
}

// ── Connections list ─────────────────────────────────────────────────────────

const connBody = () => document.getElementById('tbl-connections-body') as HTMLElement;
const connEmpty = () => document.getElementById('connections-empty') as HTMLElement;

function renderConnections(): void {
   const tbody = connBody();
   [...tbody.querySelectorAll<HTMLElement>('.net-item-card[data-node-key]')].forEach(r => r.remove());
   if (connections.size === 0) {
      connEmpty().hidden = false;
      return;
   }
   connEmpty().hidden = true;
   for (const [key, c] of connections) {
      tbody.appendChild(buildConnectionCard(key, c));
   }
}

function buildConnectionCard(key: string, c: ConnectionData): HTMLDivElement {
   const div = document.createElement('div');
   div.className = 'net-item-card';
   div.dataset.nodeKey = key;

   const dirLabel = c.inbound ? '↓ Inbound' : '↑ Outbound';
   const name = c.peerName ?? '…';
   const avatarKey = c.peerKey ?? null;
   const relayBadgeHtml = c.relay ? ` <span class="badge-relay">Relay</span>` : '';

   div.innerHTML = `
        <div class="net-item-header">
            <div class="net-item-user">
                <span class="net-item-name">${escHtml(name)}${relayBadgeHtml}</span>
            </div>
        </div>
        <div class="net-item-details">
            <div class="net-detail-row">
                <span class="detail-label">Address:</span>
                <code>${escHtml(key)}</code>
            </div>
            <div class="net-detail-row">
                <span class="detail-label">Direction:</span>
                <span>${dirLabel}</span>
            </div>
        </div>
        <div class="net-item-actions">
            <button class="btn-leave" type="button">Leave</button>
        </div>`;

   div.querySelector<HTMLElement>('.net-item-user')!.prepend(avatarImg(avatarKey));

   div.querySelector<HTMLButtonElement>('.btn-leave')!.addEventListener('click', async () => {
      try {
         await graffiti.disconnect(c.host, c.port);
      } catch (e) {
         alert(`Disconnect failed: ${(e as Error).message}`);
      }
   });

   return div;
}

function upsertConnectionRow(key: string): void {
   const c = connections.get(key)!;
   const existing = connBody().querySelector<HTMLElement>(`.net-item-card[data-node-key="${CSS.escape(key)}"]`);
   if (existing) existing.replaceWith(buildConnectionCard(key, c));
   else {
      connEmpty().hidden = true;
      connBody().appendChild(buildConnectionCard(key, c));
   }
}

function removeConnectionRow(key: string): void {
   connBody().querySelector<HTMLElement>(`.net-item-card[data-node-key="${CSS.escape(key)}"]`)?.remove();
   if (connections.size === 0) connEmpty().hidden = false;
}

// ── Discovery list ───────────────────────────────────────────────────────────

const discBody = () => document.getElementById('tbl-discovered-body') as HTMLElement;
const discEmpty = () => document.getElementById('discover-empty') as HTMLElement;
const discStatus = () => document.getElementById('discover-status') as HTMLElement;
const btnDiscover = () => document.getElementById('btn-discover') as HTMLButtonElement;

function buildDiscoverCard(d: DiscoveredNode): HTMLDivElement {
   const div = document.createElement('div');
   div.className = 'net-item-card';
   div.dataset.discoverKey = d.key;

   const ipStr = d.ips.join(', ');
   const isConn = [...connections.values()].some(c => d.ips.includes(c.host) && c.port === d.port);
   const actionHtml = isConn
      ? `<span class="status-connected">● Connected</span>`
      : `<button class="btn-connect" type="button">Connect</button>`;
   const relayBadgeHtml = d.relay ? ` <span class="badge-relay">Relay</span>` : '';

   div.innerHTML = `
        <div class="net-item-header">
            <div class="net-item-user">
                <span class="net-item-name">${escHtml(d.name)}${relayBadgeHtml}</span>
            </div>
        </div>
        <div class="net-item-details">
            <div class="net-detail-row">
                <span class="detail-label">Addresses:</span>
                <code>${escHtml(ipStr)}</code>
            </div>
            <div class="net-detail-row">
                <span class="detail-label">Port:</span>
                <span>${d.port}</span>
            </div>
        </div>
        <div class="net-item-actions">
            ${actionHtml}
        </div>`;

   div.querySelector<HTMLElement>('.net-item-user')!.prepend(avatarImg(d.key));

   div.querySelector<HTMLButtonElement>('.btn-connect')?.addEventListener('click', async (e: MouseEvent) => {
      const btn = e.currentTarget as HTMLButtonElement;
      btn.disabled = true;
      btn.textContent = 'Connecting…';
      try {
         await graffiti.connect(d.ips[0], d.port);
         btn.textContent = 'Connected';
      } catch (err) {
         btn.disabled = false;
         btn.textContent = 'Connect';
         alert(`Connect failed: ${(err as Error).message}`);
      }
   });

   return div;
}

function renderDiscoverRow(d: DiscoveredNode): void {
   const existing = discBody().querySelector<HTMLElement>(`.net-item-card[data-discover-key="${CSS.escape(d.key)}"]`);
   if (existing) existing.replaceWith(buildDiscoverCard(d));
   else {
      discEmpty().hidden = true;
      discBody().appendChild(buildDiscoverCard(d));
   }
}

function refreshDiscoverConnectedState(): void {
   for (const [, d] of discovered) renderDiscoverRow(d);
}

// ── Server card ───────────────────────────────────────────────────────────────

function setServerStopped(): void {
   (document.getElementById('server-stopped') as HTMLElement).hidden = false;
   (document.getElementById('server-running') as HTMLElement).hidden = true;
}

function setServerRunning(port: number): void {
   (document.getElementById('server-stopped') as HTMLElement).hidden = true;
   (document.getElementById('server-running') as HTMLElement).hidden = false;
   (document.getElementById('server-port-display') as HTMLElement).textContent = String(port);
}

async function loadServerStatus(): Promise<void> {
   try {
      const s = await graffiti.serverStatus();
      if (s.running) setServerRunning(s.port);
      else setServerStopped();
   } catch (_) {
      setServerStopped();
   }
}

// ── Initial load ──────────────────────────────────────────────────────────────

async function loadConnections(): Promise<void> {
   try {
      const res = await graffiti.listConnections();
      connections.clear();
      for (const c of (res.connections ?? [])) {
         const key = nodeKey(c.host, c.port);
         connections.set(key, c);
      }
      renderConnections();
   } catch (e) {
      console.error('[network] loadConnections error:', e);
   }
}

// ── WebSocket event handlers ──────────────────────────────────────────────────

onWsEvent('node_connected', (msg: Record<string, unknown>) => {
   const key = nodeKey(msg.host as string, msg.port as number);
   connections.set(key, {host: msg.host as string, port: msg.port as number, inbound: msg.inbound as boolean});
   upsertConnectionRow(key);
   refreshDiscoverConnectedState();
});

onWsEvent('node_disconnected', (msg: Record<string, unknown>) => {
   const key = nodeKey(msg.host as string, msg.port as number);
   connections.delete(key);
   removeConnectionRow(key);
   refreshDiscoverConnectedState();
});

onWsEvent('node_identified', (msg: Record<string, unknown>) => {
   const key = nodeKey(msg.host as string, msg.port as number);
   const c = connections.get(key);
   if (c) {
      c.peerKey = msg.peerKey as string;
      c.peerName = msg.peerName as string;
      c.relay = Boolean(msg.relay);
      upsertConnectionRow(key);
   }
});

onWsEvent('discover_result', (msg: Record<string, unknown>) => {
   const d: DiscoveredNode = {
      key: msg.key as string,
      ips: msg.ips as string[],
      port: msg.port as number,
      name: (msg.name as string) ?? peerNameFromKey(msg.key as string),
      relay: Boolean(msg.relay),
   };
   discovered.set(msg.key as string, d);
   renderDiscoverRow(d);
});

onWsEvent('discover_done', () => {
   btnDiscover().disabled = false;
   btnDiscover().innerHTML = '<span class="material-symbols-outlined" style="font-size: 1.1rem;">search</span> Discover';
   const status = discStatus();
   status.textContent = `Found ${discovered.size} node${discovered.size !== 1 ? 's' : ''}.`;
   status.hidden = false;
});

onWsEvent('node_relay_update', (msg: Record<string, unknown>) => {
   const relay = Boolean(msg.relay);
   setRelayUi(relay);
   setRelayStatus(`Relay mode is ${relay ? 'ON' : 'OFF'}.`);
});


async function triggerDiscovery(showAlertOnError = true): Promise<void> {
   const btn = btnDiscover();
   if (!btn) return;
   btn.disabled = true;
   btn.innerHTML = '<span class="material-symbols-outlined" style="font-size: 1.1rem;">hourglass_empty</span> Scanning…';
   discStatus().hidden = true;
   discovered.clear();
   const body = discBody();
   if (body) {
      [...body.querySelectorAll<HTMLElement>('.net-item-card[data-discover-key]')].forEach(r => r.remove());
   }
   discEmpty().hidden = false;
   try {
      const scanFull = (document.getElementById('discover-scan-full') as HTMLInputElement | null)?.checked ?? false;
      await graffiti.discover(scanFull);
   } catch (e) {
      btn.disabled = false;
      btn.innerHTML = '<span class="material-symbols-outlined" style="font-size: 1.1rem;">search</span> Discover';
      if (showAlertOnError) {
         alert(`Discovery failed: ${(e as Error).message}`);
      } else {
         console.warn(`Initial discovery failed: ${(e as Error).message}`);
      }
   }
}

// ── Button wiring ─────────────────────────────────────────────────────────────

function wireButtons(): void {
   Promise.all([
      graffiti.getStore('graffiti:last-connect-host'),
      graffiti.getStore('graffiti:last-connect-port')
   ]).then(([savedHost, savedPort]) => {
      if (savedHost) {
         (document.getElementById('manual-connect-host') as HTMLInputElement).value = savedHost;
      }
      if (savedPort) {
         (document.getElementById('manual-connect-port') as HTMLInputElement).value = savedPort;
      }
   }).catch(e => console.error('Failed to load manual connection settings:', e));

   document.getElementById('btn-manual-connect')!.addEventListener('click', async () => {
      const host = (document.getElementById('manual-connect-host') as HTMLInputElement).value.trim();
      const port = parseInt((document.getElementById('manual-connect-port') as HTMLInputElement).value, 10);
      const statusEl = document.getElementById('manual-connect-status') as HTMLElement;
      if (!host) {
         statusEl.textContent = 'Enter a host address.';
         statusEl.hidden = false;
         return;
      }
      if (!port || port < 1 || port > 65535) {
         statusEl.textContent = 'Enter a valid port (1–65535).';
         statusEl.hidden = false;
         return;
      }
      const btn = document.getElementById('btn-manual-connect') as HTMLButtonElement;
      btn.disabled = true;
      btn.textContent = 'Connecting…';
      statusEl.hidden = true;
      try {
         await graffiti.connect(host, port);
         statusEl.textContent = `Connected to ${host}:${port}.`;
         statusEl.hidden = false;
         await graffiti.setStore('graffiti:last-connect-host', host);
         await graffiti.setStore('graffiti:last-connect-port', String(port));
      } catch (e) {
         statusEl.textContent = `Failed: ${(e as Error).message}`;
         statusEl.hidden = false;
      } finally {
         btn.disabled = false;
         btn.textContent = 'Connect';
      }
   });

   document.getElementById('btn-discover')!.addEventListener('click', () => {
      void triggerDiscovery(true);
   });

   document.getElementById('btn-server-start')!.addEventListener('click', async () => {
      const port = parseInt((document.getElementById('server-port-input') as HTMLInputElement).value, 10);
      if (isNaN(port) || port < 0 || port > 65535) {
         alert('Enter a valid port (0–65535).');
         return;
      }
      try {
         const res = await graffiti.startServer(port);
         const actualPort = res.port ?? port;
         setServerRunning(actualPort);
      } catch (e) {
         alert(`Failed to start server: ${(e as Error).message}`);
      }
   });

   document.getElementById('btn-server-stop')!.addEventListener('click', async () => {
      try {
         await graffiti.stopServer();
         setServerStopped();
      } catch (e) {
         alert(`Failed to stop server: ${(e as Error).message}`);
      }
   });

   const [relayOff, relayOn] = relayInputs();
   const setRelay = async (enabled: boolean) => {
      relayOff.disabled = true;
      relayOn.disabled = true;
      setRelayStatus('Updating relay mode...');
      try {
         const status = await graffiti.setNodeRelay(enabled);
         setRelayUi(status.relay);
         setRelayStatus(`Relay mode is ${status.relay ? 'ON' : 'OFF'}.`);
      } catch (e) {
         setRelayStatus(`Failed to update relay mode: ${(e as Error).message}`, true);
         await loadRelayMode();
      } finally {
         relayOff.disabled = false;
         relayOn.disabled = false;
      }
   };
   relayOff.addEventListener('change', () => {
      if (relayOff.checked) void setRelay(false);
   });
   relayOn.addEventListener('change', () => {
      if (relayOn.checked) void setRelay(true);
   });
}

// ── Utility ───────────────────────────────────────────────────────────────────

function escHtml(str: string): string {
   return String(str)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function peerNameFromKey(key: string): string {
   if (!key) return 'Unknown';
   return key.substring(0, 8) + '…';
}

// ── Section lifecycle ─────────────────────────────────────────────────────────

let buttonsWired = false;
let hasDiscoveredInitial = false;

onSectionShow('section-network', async () => {
   if (!buttonsWired) {
      wireButtons();
      buttonsWired = true;
   }
   await Promise.all([loadNodeIdentity(), loadRelayMode(), loadConnections(), loadServerStatus()]);
});

// Run discover on app start (when WebSocket connection is open)
onWsOpen(() => {
   if (!hasDiscoveredInitial) {
      hasDiscoveredInitial = true;
      void triggerDiscovery(false);
   }
});

