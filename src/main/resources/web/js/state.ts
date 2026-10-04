import {graffiti} from './graffiti-api.js';
import {onWsEvent, onWsOpen} from './app.js';
import {applyNodesState, applyRelayState, applyServerState, updateNetTransferIndicator} from './network.js';
import {applyContactsState, applyWhitelistState} from './identity.js';
import {applyActiveCommandsState, applyMessagesContactsState, applyMessagesState, updateMsgIndicator} from './messages.js';

let currentVersion = -1;
let lastNodesVersion = -1;
let lastIdentitiesVersion = -1;
let lastPeersVersion = -1;
let lastMessagesVersion = -1;
let lastRelay: boolean | undefined = undefined;
let lastWhitelist: boolean | undefined = undefined;

let isSyncing = false;
let pendingSync = false;

export async function syncState(forced = false): Promise<void> {
   if (isSyncing) {
      pendingSync = true;
      return;
   }
   isSyncing = true;
   try {
      const state = await graffiti.getState();
      if (!forced && state.version <= currentVersion) {
         return;
      }
      currentVersion = state.version;

      // 1. Indicators & Active Background Commands (always update)
      updateNetTransferIndicator(Boolean(state.transferring));
      updateMsgIndicator(Boolean(state.encoding));
      if (state.activeCommands) {
         applyActiveCommandsState(state.activeCommands);
      }

      // 2. Nodes
      if (forced || state.nodesVersion !== lastNodesVersion) {
         lastNodesVersion = state.nodesVersion;
         applyNodesState(state.nodes);
      }

      // 3. Contacts (identities & peers)
      if (forced || state.identitiesVersion !== lastIdentitiesVersion || state.peersVersion !== lastPeersVersion) {
         lastIdentitiesVersion = state.identitiesVersion;
         lastPeersVersion = state.peersVersion;
         void applyContactsState(state.identities, state.peers);
         applyMessagesContactsState(state.identities, state.peers);
      }

      // 4. Messages
      if (forced || state.messagesVersion !== lastMessagesVersion) {
         lastMessagesVersion = state.messagesVersion;
         applyMessagesState(state.messageKeys);
      }

      // 5. Relay
      if (typeof state.relay === 'boolean' && (forced || state.relay !== lastRelay)) {
         lastRelay = state.relay;
         applyRelayState(state.relay);
      }

      // 6. Whitelist
      if (typeof state.whitelist === 'boolean' && (forced || state.whitelist !== lastWhitelist)) {
         lastWhitelist = state.whitelist;
         applyWhitelistState(state.whitelist);
      }

      // 7. Server
      if (state.server) {
         applyServerState(state.server.running, state.server.port);
      }
   } catch (e) {
      console.warn('Failed to sync state:', e);
   } finally {
      isSyncing = false;
      if (pendingSync) {
         pendingSync = false;
         void syncState(false);
      }
   }
}

// ── Event Triggers ────────────────────────────────────────────────────────────

// 1. WebSocket state_changed event
onWsEvent('state_changed', (msg: Record<string, unknown>) => {
   const v = typeof msg.version === 'number' ? msg.version : 0;
   if (v > currentVersion) {
      void syncState(false);
   }
});

// 2. WebSocket opened / reconnected
onWsOpen(() => {
   void syncState(true);
});

// 3. Foreground / resume events
function onForeground(): void {
   void syncState(true);
}

document.addEventListener('visibilitychange', () => {
   if (document.visibilityState === 'visible') {
      onForeground();
   }
});

window.addEventListener('focus', () => {
   onForeground();
});

window.addEventListener('pageshow', () => {
   onForeground();
});

// 4. Initial sync on module load
void syncState(true);
