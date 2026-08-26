import { onWsEvent } from './app.js';
import { graffiti } from './graffiti-api.js';

export interface LogEntry {
   id: string;
   timestamp: number;
   category: 'download' | 'send' | 'received' | string;
   status: 'in_progress' | 'started' | 'completed' | 'failed' | string;
   title: string;
   details: string;
   name?: string;
   size?: number;
   peer?: string;
}

const logEntries: LogEntry[] = [];

function formatBytes(bytes?: number): string {
   if (bytes === undefined || bytes === null || bytes <= 0) return '';
   if (bytes < 1024) return `${bytes} B`;
   if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
   if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
   return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function formatTime(timestamp: number): string {
   const d = new Date(timestamp);
   return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function getTransferDisplayInfo(category: string, status: string): { icon: string; statusLabel: string; statusClass: string } {
   const isSend = category === 'send';
   const isInProgress = status === 'started' || status === 'in_progress';
   const isCompleted = status === 'completed';
   const isFailed = status === 'failed';

   if (isSend) {
      if (isInProgress) {
         return { icon: 'upload', statusLabel: 'Sending', statusClass: 'log-status-in_progress' };
      }
      if (isCompleted) {
         return { icon: 'task_alt', statusLabel: 'Sent', statusClass: 'log-status-completed' };
      }
      if (isFailed) {
         return { icon: 'error', statusLabel: 'Failed', statusClass: 'log-status-failed' };
      }
      return { icon: 'upload', statusLabel: status.replace('_', ' '), statusClass: `log-status-${status}` };
   } else {
      if (isInProgress) {
         return { icon: 'download', statusLabel: 'Receiving', statusClass: 'log-status-in_progress' };
      }
      if (isCompleted) {
         return { icon: 'download_done', statusLabel: 'Received', statusClass: 'log-status-completed' };
      }
      if (isFailed) {
         return { icon: 'error', statusLabel: 'Failed', statusClass: 'log-status-failed' };
      }
      return { icon: 'download', statusLabel: status.replace('_', ' '), statusClass: `log-status-${status}` };
   }
}

export function addLogEntry(entry: LogEntry): void {
   // Only keep send and download/receive transfer logs
   const cat = entry.category?.toLowerCase() ?? '';
   if (cat !== 'send' && cat !== 'download' && cat !== 'received') {
      return;
   }

   // Prevent duplicate entries with identical IDs or update existing ones
   const existingIndex = logEntries.findIndex(e => e.id === entry.id);
   if (existingIndex >= 0) {
      logEntries[existingIndex] = entry;
   } else {
      logEntries.unshift(entry);
      // Keep up to 200 logs in memory
      if (logEntries.length > 200) logEntries.pop();
   }
   renderLogs();
}

export function logActivity(
   category: LogEntry['category'],
   status: LogEntry['status'],
   title: string,
   details: string,
   name?: string,
   size?: number,
   peer?: string
): void {
   addLogEntry({
      id: `log_local_${Date.now()}_${Math.floor(Math.random() * 1000)}`,
      timestamp: Date.now(),
      category,
      status,
      title,
      details,
      name,
      size,
      peer
   });
}

function renderLogs(): void {
   const container = document.getElementById('logs-container');
   const summaryEl = document.getElementById('logs-summary-count');
   const navBadge = document.getElementById('nav-transfer-badge');

   const activeIn = logEntries.filter(e => (e.category === 'download' || e.category === 'received') && (e.status === 'in_progress' || e.status === 'started')).length;
   const activeOut = logEntries.filter(e => (e.category === 'send') && (e.status === 'in_progress' || e.status === 'started')).length;
   const activeTransfers = activeIn + activeOut;

   if (summaryEl) {
      let activeText = '';
      if (activeTransfers > 0) {
         const parts = [];
         if (activeOut > 0) parts.push(`${activeOut} outgoing`);
         if (activeIn > 0) parts.push(`${activeIn} incoming`);
         activeText = ` (${parts.join(', ')} active)`;
      }
      summaryEl.textContent = `${logEntries.length} transfer${logEntries.length === 1 ? '' : 's'}${activeText}`;
   }

   if (navBadge) {
      if (activeTransfers > 0) {
         const parts = [];
         if (activeOut > 0) parts.push(`↑${activeOut}`);
         if (activeIn > 0) parts.push(`↓${activeIn}`);
         navBadge.textContent = parts.join(' ');
         navBadge.hidden = false;
         navBadge.title = `Active Transfer in progress: ${activeOut} outgoing, ${activeIn} incoming`;
      } else {
         navBadge.hidden = true;
      }
   }

   if (!container) return;

   if (logEntries.length === 0) {
      container.innerHTML = '<div class="empty-row" id="logs-empty">No transfer activity recorded yet.</div>';
      return;
   }

   container.innerHTML = logEntries.map(entry => {
      const { icon, statusLabel, statusClass } = getTransferDisplayInfo(entry.category, entry.status);
      const sizeStr = entry.size ? ` • ${formatBytes(entry.size)}` : '';
      const peerStr = entry.peer ? ` • ${entry.peer}` : '';

      return `
         <div class="log-entry-card" data-id="${entry.id}">
            <div class="log-entry-left">
               <span class="material-symbols-outlined log-icon log-icon-${entry.category}">${icon}</span>
               <div class="log-details">
                  <div class="log-title-row">
                     <span class="log-title">${entry.title}</span>
                     <span class="log-message">${entry.details}${sizeStr}${peerStr}</span>
                  </div>
               </div>
            </div>
            <div class="log-entry-right">
               <span class="log-status-pill ${statusClass}">${statusLabel}</span>
               <span class="log-time">${formatTime(entry.timestamp)}</span>
            </div>
         </div>
      `;
   }).join('');
}

// ── Event Handlers & Subscriptions ───────────────────────────────────────────

export async function fetchLogs(): Promise<void> {
   try {
      const res = await graffiti.getLogs();
      if (res.ok && Array.isArray(res.logs)) {
         logEntries.length = 0;
         res.logs.forEach((msg: any) => {
            logEntries.push({
               id: String(msg.id || `log_${Date.now()}`),
               timestamp: Number(msg.timestamp) || Date.now(),
               category: String(msg.category || ''),
               status: String(msg.status || 'info'),
               title: String(msg.title || 'Transfer Event'),
               details: String(msg.details || ''),
               name: msg.name ? String(msg.name) : undefined,
               size: typeof msg.size === 'number' ? msg.size : undefined,
               peer: msg.peer ? String(msg.peer) : undefined
            });
         });
         renderLogs();
      }
   } catch (err) {
      console.error('[logs] Failed to fetch server logs:', err);
   }
}

onWsEvent('log_event', (msg: Record<string, unknown>) => {
   addLogEntry({
      id: String(msg.id || `log_${Date.now()}`),
      timestamp: Number(msg.timestamp) || Date.now(),
      category: String(msg.category || ''),
      status: String(msg.status || 'info'),
      title: String(msg.title || 'Transfer Event'),
      details: String(msg.details || ''),
      name: msg.name ? String(msg.name) : undefined,
      size: typeof msg.size === 'number' ? msg.size : undefined,
      peer: msg.peer ? String(msg.peer) : undefined
   });
});

onWsEvent('logs_cleared', () => {
   logEntries.length = 0;
   renderLogs();
});

function initLogControls(): void {
   const clearBtn = document.getElementById('btn-logs-clear');
   clearBtn?.addEventListener('click', async () => {
      try {
         await graffiti.clearLogs();
      } catch (_) {}
      logEntries.length = 0;
      renderLogs();
   });
   fetchLogs();
}

if (document.readyState === 'loading') {
   document.addEventListener('DOMContentLoaded', initLogControls);
} else {
   initLogControls();
}
