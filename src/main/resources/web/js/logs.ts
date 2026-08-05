import { onWsEvent } from './app.js';

export interface LogEntry {
   id: string;
   timestamp: number;
   category: 'download' | 'send' | 'sync' | 'network' | string;
   status: 'in_progress' | 'completed' | 'failed' | 'info' | string;
   title: string;
   details: string;
   name?: string;
   size?: number;
   peer?: string;
}

const logEntries: LogEntry[] = [];
let activeFilter = 'all';

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

function getCategoryIcon(category: string): string {
   switch (category) {
      case 'download': return 'download';
      case 'send': return 'send';
      case 'sync': return 'sync';
      case 'network': return 'lan';
      default: return 'info';
   }
}

export function addLogEntry(entry: LogEntry): void {
   // Prevent duplicate entries with identical IDs
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
   if (!container) return;

   const filtered = activeFilter === 'all'
      ? logEntries
      : logEntries.filter(e => e.category === activeFilter);

   if (summaryEl) {
      const activeDownloads = logEntries.filter(e => e.category === 'download' && (e.status === 'in_progress' || e.status === 'started')).length;
      const downloadText = activeDownloads > 0 ? ` (${activeDownloads} active download${activeDownloads > 1 ? 's' : ''})` : '';
      summaryEl.textContent = `${filtered.length} of ${logEntries.length} entries${downloadText}`;
   }

   if (filtered.length === 0) {
      container.innerHTML = `<div class="empty-row" id="logs-empty">No ${activeFilter === 'all' ? 'activity' : activeFilter} logs recorded.</div>`;
      return;
   }

   container.innerHTML = filtered.map(entry => {
      const icon = getCategoryIcon(entry.category);
      const sizeStr = entry.size ? ` • ${formatBytes(entry.size)}` : '';
      const peerStr = entry.peer ? ` • ${entry.peer}` : '';
      const statusLabel = entry.status.replace('_', ' ');

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
               <span class="log-status-pill log-status-${entry.status}">${statusLabel}</span>
               <span class="log-time">${formatTime(entry.timestamp)}</span>
            </div>
         </div>
      `;
   }).join('');
}

// ── Event Handlers & Subscriptions ───────────────────────────────────────────

onWsEvent('log_event', (msg: Record<string, unknown>) => {
   addLogEntry({
      id: String(msg.id || `log_${Date.now()}`),
      timestamp: Number(msg.timestamp) || Date.now(),
      category: String(msg.category || 'network'),
      status: String(msg.status || 'info'),
      title: String(msg.title || 'System Event'),
      details: String(msg.details || ''),
      name: msg.name ? String(msg.name) : undefined,
      size: typeof msg.size === 'number' ? msg.size : undefined,
      peer: msg.peer ? String(msg.peer) : undefined
   });
});

function initLogControls(): void {
   const filterBtns = document.querySelectorAll<HTMLButtonElement>('.logs-filter-btn');
   filterBtns.forEach(btn => {
      btn.addEventListener('click', () => {
         filterBtns.forEach(b => b.classList.remove('is-active'));
         btn.classList.add('is-active');
         activeFilter = btn.dataset.filter || 'all';
         renderLogs();
      });
   });

   const clearBtn = document.getElementById('btn-logs-clear');
   clearBtn?.addEventListener('click', () => {
      logEntries.length = 0;
      renderLogs();
   });
}

if (document.readyState === 'loading') {
   document.addEventListener('DOMContentLoaded', initLogControls);
} else {
   initLogControls();
}

