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
export interface ApiOk {
    ok: true;
}
export interface IdentityEntry {
    name: string;
    key: string;
    /** The Peer key derived from this identity — used as the recipient key when sending. */
    peerKey: string;
}
export interface PeerEntry {
    name: string;
    key: string;
}
export interface MessageEntry {
    key: string;
    author: string;
    recipient: string;
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
export declare const graffiti: {
    listIdentities(): Promise<ListIdentitiesResponse>;
    createIdentity(seed?: string): Promise<CreateIdentityResponse>;
    removeIdentity(key: string): Promise<ApiOk>;
    listPeers(): Promise<ListPeersResponse>;
    importPeer(file: File): Promise<ImportPeerResponse>;
    removePeer(key: string): Promise<ApiOk>;
    exportPeer(key: string): Promise<void>;
    listMessages(): Promise<ListMessagesResponse>;
    contentUrl(key: string): string;
    removeMessage(key: string): Promise<ApiOk>;
    exportMessage(key: string): Promise<void>;
    sendText(identityKey: string, peerKey: string, text: string): Promise<SendMessageResponse>;
    sendFile(identityKey: string, peerKey: string, file: File): Promise<SendMessageResponse>;
    retryMissingContent(): Promise<ApiOk>;
    avatarUrl(key: string): string;
    listConnections(): Promise<ListConnectionsResponse>;
    serverStatus(): Promise<ServerStatus>;
    startServer(port: number): Promise<ApiOk & {
        port?: number;
    }>;
    stopServer(): Promise<ApiOk>;
    discover(): Promise<ApiOk>;
    connect(host: string, port: number): Promise<ApiOk>;
    sync(host: string, port: number): Promise<ApiOk>;
    disconnect(host: string, port: number): Promise<ApiOk>;
    nodeInfo(): Promise<NodeInfo>;
    nodeRelayStatus(): Promise<NodeRelayStatus>;
    setNodeRelay(enabled: boolean): Promise<NodeRelayStatus>;
};
