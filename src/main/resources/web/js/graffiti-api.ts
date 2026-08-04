/**
 * Graffiti API Client — ES module (TypeScript)
 *
 * Import: import { graffiti } from './graffiti-api.js';
 *
 * Thin async wrapper around the GraffitiAPI HTTP endpoints served by the
 * embedded NanoHTTPD server. All methods return Promises. On an application-
 * level error (ok: false) the Promise is rejected with the server's error
 * message. On a network/HTTP error the Promise is rejected with a generic
 * message.
 */

// ── Types ─────────────────────────────────────────────────────────────────────

export interface ApiOk {
   ok: true;
}

export interface IdentityEntry {
   name: string;
   key: string;
   /** The Peer key derived from this identity — used as the recipient key when sending. */
   peerKey: string;
   persistent?: boolean;
}

export interface PeerEntry {
   name: string;
   key: string;
}

export interface MessageEntry {
   key: string;
   author: string;
   authorKey?: string;
   recipient: string;
   recipientKey?: string;
   name: string;
   size: number;
   type: string;
   created: number | string;
}

export interface ConnectionEntry {
   host: string;
   port: number;
   inbound: boolean;
   peerKey?: string;
   peerName?: string;
   relay?: boolean;
}

export interface NodeInfo extends ApiOk {
   peerKey: string;
   peerName: string;
   defaultP2PPort: number;
   ips: string[];
}

export interface NodeRelayStatus extends ApiOk {
   relay: boolean;
}


export interface ServerStatus extends ApiOk {
   running: boolean;
   port: number;
}

export interface ListIdentitiesResponse extends ApiOk {
   identities: IdentityEntry[];
}

export interface ListPeersResponse extends ApiOk {
   peers: PeerEntry[];
}

export interface ListMessagesResponse extends ApiOk {
   messages: MessageEntry[];
}

export interface ListConnectionsResponse extends ApiOk {
   connections: ConnectionEntry[];
}

export interface CreateIdentityResponse extends ApiOk {
   name: string;
   key: string;
}

export interface ImportPeerResponse extends ApiOk {
   name: string;
   key: string;
}

export interface SendMessageResponse extends ApiOk {
   key: string;
}

export interface OpenPackResponse extends ApiOk {
   sessionId: string;
   port: number;
   url: string;
}

export interface OpenPackParams {
   path?: string;
   encKey?: string;
   password?: string;
}

export interface StorageItem {
   name: string;
   key: string;
   size: number;
}

export interface GetStorageResponse extends ApiOk {
   overall: number;
   storage: StorageItem[];
}

export interface PurgeStorageResponse extends ApiOk {
   purged: number;
}

export interface GetQuotaResponse extends ApiOk {
   quota: number;
}

// ── Internal helpers ──────────────────────────────────────────────────────────

async function parseJson<T extends { ok: boolean; error?: string }>(res: Response): Promise<T> {
   let json: T;
   let text = '';
   try {
      text = await res.clone().text();
      json = await res.json() as T;
   } catch (_) {
      throw new Error(`HTTP ${res.status}: response was not JSON. Content: "${text}"`);
   }
   if (!json.ok) throw new Error(json.error || `Request failed (HTTP ${res.status})`);
   return json;
}

async function get<T extends { ok: boolean; error?: string }>(
   path: string,
   params: Record<string, string> = {},
): Promise<T> {
   const url = new URL(path, window.location.origin);
   for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
   return parseJson<T>(await fetch(url.toString()));
}

async function putJson<T extends { ok: boolean; error?: string }>(
   path: string,
   body: Record<string, any>,
): Promise<T> {
   const url = new URL(path, window.location.origin);
   const res = await fetch(url.toString(), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
   });
   return parseJson<T>(res);
}


async function download(path: string, params: Record<string, string> = {}): Promise<void> {
   const url = new URL(path, window.location.origin);
   for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);

   if ((window as any).Android && (window as any).Android.download) {
      (window as any).Android.download(url.toString());
      return;
   }

   const a = document.createElement('a');
   a.href = url.toString();
   document.body.appendChild(a);
   a.click();
   document.body.removeChild(a);
}


// ── Public API ────────────────────────────────────────────────────────────────

export const graffiti = {

   // ── Identity ──────────────────────────────────────────────────────────

   listIdentities(): Promise<ListIdentitiesResponse> {
      return get('/api/identities');
   },

   createIdentity(seed: string): Promise<CreateIdentityResponse> {
      return putJson('/api/identity/create', { seed }) as Promise<CreateIdentityResponse>;
   },

   removeIdentity(key: string): Promise<ApiOk> {
      return get('/api/identity/remove', {key});
   },

   setIdentityPersistence(key: string, persistent: boolean): Promise<ApiOk> {
      return get('/api/identity/persist', {key, persistent: String(persistent)});
   },


   // ── Peer ──────────────────────────────────────────────────────────────

   listPeers(): Promise<ListPeersResponse> {
      return get('/api/peers');
   },

   importPeer(file: File): Promise<ImportPeerResponse> {
      const fd = new FormData();
      fd.append('file', file);
      // TODO
      return {} as Promise<ImportPeerResponse>
      // return postMultipart('/api/peer/import', fd);
   },

   removePeer(key: string): Promise<ApiOk> {
      return get('/api/peer/remove', {key});
   },

   exportPeer(key: string): Promise<void> {
      return download('/api/peer/export', {key});
   },

   // ── Message ───────────────────────────────────────────────────────────

   listMessages(): Promise<ListMessagesResponse> {
      return get('/api/messages');
   },

   refreshMessages(): Promise<ApiOk> {
      return get('/api/messages/refresh');
   },

   contentUrl(key: string): string {
      return `/api/content?key=${encodeURIComponent(key)}`;
   },

   removeMessage(key: string): Promise<ApiOk> {
      return get('/api/message/remove', {key});
   },

   async sendText(identityKey: string, peerKey: string, text: string): Promise<SendMessageResponse> {
      const url = new URL(`/api/message/send/text?identityKey=${encodeURIComponent(identityKey)}&peerKey=${encodeURIComponent(peerKey)}`, window.location.origin);
      const res = await fetch(url.toString(), {
         method: 'PUT',
         headers: {'Content-Type': 'text/plain'},
         body: text
      });
      return parseJson<SendMessageResponse>(res);
   },

   async sendFile(identityKey: string, peerKey: string, file: File): Promise<SendMessageResponse> {
      const url = new URL(`/api/message/send/file?identityKey=${encodeURIComponent(identityKey)}&peerKey=${encodeURIComponent(peerKey)}&file=${encodeURIComponent(file.name)}`, window.location.origin);
      const res = await fetch(url.toString(), {
         method: 'PUT',
         headers: {
            'Content-Type': file.type || 'application/octet-stream',
            'file': encodeURIComponent(file.name)
         },
         body: file
      });
      return parseJson<SendMessageResponse>(res);
   },

   refresh(): Promise<ApiOk> {
      return get('/api/messages/refresh');
   },

   // ── Avatar ────────────────────────────────────────────────────────────

   avatarUrl(key: string): string {
      return `/api/avatar?key=${encodeURIComponent(key)}`;
   },

   // ── Network ───────────────────────────────────────────────────────────

   listConnections(): Promise<ListConnectionsResponse> {
      return get('/api/connections');
   },

   serverStatus(): Promise<ServerStatus> {
      return get('/api/server/status');
   },

   startServer(port: number): Promise<ApiOk & { port?: number }> {
      return get('/api/server/start', {port: String(port)});
   },

   stopServer(): Promise<ApiOk> {
      return get('/api/server/stop');
   },

   discover(scan?: boolean): Promise<ApiOk> {
      return get('/api/discover', scan ? {scan: 'true'} : {});
   },

   connect(host: string, port: number): Promise<ApiOk> {
      return get('/api/client/connect', {node: host, port: String(port)});
   },

   disconnect(host: string, port: number): Promise<ApiOk> {
      return get('/api/client/disconnect', {node:host, port: String(port)});
   },

   // ── Settings ──────────────────────────────────────────────────────────

   getStorage(): Promise<GetStorageResponse> {
      return get('/api/storage');
   },

   purgeStorage(key: string, type: 'half' | 'all'): Promise<PurgeStorageResponse> {
      return get('/api/storage/purge', {key, type}) as Promise<PurgeStorageResponse>;
   },

   getQuota(): Promise<GetQuotaResponse> {
      return get('/api/storage/quota');
   },

   setQuota(quotaBytes: number): Promise<ApiOk> {
      return get('/api/storage/quota', {quota: String(quotaBytes)});
   },


   // ── Node identity ─────────────────────────────────────────────────────

   nodeInfo(): Promise<NodeInfo> {
      return get('/api/node/info');
   },

   nodeRelayStatus(): Promise<NodeRelayStatus> {
      return get('/api/node/relay');
   },

   setNodeRelay(enabled: boolean): Promise<NodeRelayStatus> {
      let e = ""
      if (enabled) {
         e = "true"
      } else {
         e = "false"
      }
      return get('/api/node/relay', {enabled: e}) as Promise<NodeRelayStatus>;
   },

   async getStore(key: string): Promise<string> {
      try {
         const res = await get<{ ok: boolean; value?: string }>('/api/store', {key});
         return res.value || '';
      } catch (_) {
         return '';
      }
   },

   async setStore(key: string, value: string): Promise<ApiOk> {
      const url = new URL(`/api/store?key=${encodeURIComponent(key)}`, window.location.origin);
      const res = await fetch(url.toString(), {
         method: 'PUT',
         body: value
      });
      return parseJson<ApiOk>(res);
   },

   // ── Pack Viewing ────────────────────────────────────────────────────────
   openPack(params: OpenPackParams): Promise<OpenPackResponse> {
      return putJson('/api/pack/open', params);
   },

   closePack(sessionId: string): Promise<ApiOk> {
      return get('/api/pack/close', { sessionId });
   },
};

export async function openPackFile(params: { path?: string; encKey?: string; name?: string; password?: string }): Promise<void> {
   try {
      await graffiti.openPack(params);
   } catch (err: any) {
      const errMsg = err?.message || String(err);
      if (errMsg.includes('PASSWORD_REQUIRED') || errMsg.includes('INVALID_PASSWORD')) {
         const pass = prompt(`Enter password for ${params.name || 'pack'}:`);
         if (pass) {
            await openPackFile({ ...params, password: pass });
         }
      } else {
         alert(`Failed to open pack: ${errMsg}`);
      }
   }
}