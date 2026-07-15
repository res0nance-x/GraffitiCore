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
// ── Internal helpers ──────────────────────────────────────────────────────────
async function parseJson(res) {
    let json;
    let text = '';
    try {
        text = await res.clone().text();
        json = await res.json();
    }
    catch (_) {
        throw new Error(`HTTP ${res.status}: response was not JSON. Content: "${text}"`);
    }
    if (!json.ok)
        throw new Error(json.error || `Request failed (HTTP ${res.status})`);
    return json;
}
async function get(path, params = {}) {
    const url = new URL(path, window.location.origin);
    for (const [k, v] of Object.entries(params))
        url.searchParams.set(k, v);
    return parseJson(await fetch(url.toString()));
}
async function download(path, params = {}) {
    const url = new URL(path, window.location.origin);
    for (const [k, v] of Object.entries(params))
        url.searchParams.set(k, v);
    const res = await fetch(url.toString());
    if (!res.ok) {
        let msg = `HTTP ${res.status}`;
        try {
            msg = (await res.json()).error || msg;
        }
        catch (_) { /* ignore */
        }
        throw new Error(msg);
    }
    const blob = await res.blob();
    const filename = contentDispositionFilename(res) ?? 'download';
    triggerSave(blob, filename);
}
function contentDispositionFilename(response) {
    const cd = response.headers.get('Content-Disposition');
    if (!cd)
        return null;
    const match = cd.match(/filename="?([^";]+)"?/);
    return match ? match[1].trim() : null;
}
function triggerSave(blob, filename) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(a.href);
}
// ── Public API ────────────────────────────────────────────────────────────────
export const graffiti = {
    // ── Identity ──────────────────────────────────────────────────────────
    listIdentities() {
        return get('/api/identities');
    },
    createIdentity(seed) {
        return get('/api/identity/create', { seed });
    },
    removeIdentity(key) {
        return get('/api/identity/remove', { key });
    },
    // ── Peer ──────────────────────────────────────────────────────────────
    listPeers() {
        return get('/api/peers');
    },
    importPeer(file) {
        const fd = new FormData();
        fd.append('file', file);
        // TODO
        return {};
        // return postMultipart('/api/peer/import', fd);
    },
    removePeer(key) {
        return get('/api/peer/remove', { key });
    },
    exportPeer(key) {
        return download('/api/peer/export', { key });
    },
    // ── Message ───────────────────────────────────────────────────────────
    listMessages() {
        return get('/api/messages');
    },
    contentUrl(key) {
        return `/api/content?key=${encodeURIComponent(key)}`;
    },
    removeMessage(key) {
        return get('/api/message/remove', { key });
    },
    exportMessage(key) {
        return download('/api/message/export', { key });
    },
    async sendText(identityKey, peerKey, text) {
        const url = new URL(`/api/message/send/text?identityKey=${encodeURIComponent(identityKey)}&peerKey=${encodeURIComponent(peerKey)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'PUT',
            headers: { 'Content-Type': 'text/plain' },
            body: text
        });
        return parseJson(res);
    },
    async sendFile(identityKey, peerKey, file) {
        const url = new URL(`/api/message/send/file?identityKey=${encodeURIComponent(identityKey)}&peerKey=${encodeURIComponent(peerKey)}&file=${encodeURIComponent(file.name)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'PUT',
            headers: {
                'Content-Type': file.type || 'application/octet-stream',
                'file': encodeURIComponent(file.name)
            },
            body: file
        });
        return parseJson(res);
    },
    refresh() {
        return get('/api/messages/refresh');
    },
    // ── Avatar ────────────────────────────────────────────────────────────
    avatarUrl(key) {
        return `/api/avatar?key=${encodeURIComponent(key)}`;
    },
    // ── Network ───────────────────────────────────────────────────────────
    listConnections() {
        return get('/api/connections');
    },
    serverStatus() {
        return get('/api/server/status');
    },
    startServer(port) {
        return get('/api/server/start', { port: String(port) });
    },
    stopServer() {
        return get('/api/server/stop');
    },
    discover(scan) {
        return get('/api/discover', scan ? { scan: 'true' } : {});
    },
    connect(host, port) {
        return get('/api/client/connect', { node: host, port: String(port) });
    },
    disconnect(host, port) {
        return get('/api/client/disconnect', { node: host, port: String(port) });
    },
    // ── Settings ──────────────────────────────────────────────────────────
    getStorage() {
        return get('/api/storage');
    },
    purgeStorage(key, type) {
        return get('/api/storage/purge', { key, type });
    },
    getQuota() {
        return get('/api/storage/quota');
    },
    setQuota(quotaBytes) {
        return get('/api/storage/quota', { quota: String(quotaBytes) });
    },
    // ── Node identity ─────────────────────────────────────────────────────
    nodeInfo() {
        return get('/api/node/info');
    },
    nodeRelayStatus() {
        return get('/api/node/relay');
    },
    setNodeRelay(enabled) {
        let e = "";
        if (enabled) {
            e = "true";
        }
        else {
            e = "false";
        }
        return get('/api/node/relay', { enabled: e });
    },
    async getStore(key) {
        try {
            const res = await get('/api/store', { key });
            return res.value || '';
        }
        catch (_) {
            return '';
        }
    },
    async setStore(key, value) {
        const url = new URL(`/api/store?key=${encodeURIComponent(key)}`, window.location.origin);
        const res = await fetch(url.toString(), {
            method: 'PUT',
            body: value
        });
        return parseJson(res);
    },
};
//# sourceMappingURL=graffiti-api.js.map