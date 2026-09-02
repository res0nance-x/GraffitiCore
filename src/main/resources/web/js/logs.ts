import {onWsEvent} from './app.js';
import {graffiti} from './graffiti-api.js';

export interface LogEntry {
   id: string;
   timestamp: number;
   details: string;
}

const logEntries: LogEntry[] = [];

function escHtml(str: string): string {
   return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
}

function formatTime(timestamp: number): string {
   const d = new Date(timestamp);
   return d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit', second: '2-digit'});
}

export function addLogEntry(entry: LogEntry): void {
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

function renderLogs(): void {
   const container = document.getElementById('logs-container');
   if (!container) return;

   if (logEntries.length === 0) {
      container.innerHTML = '<div class="empty-row" id="logs-empty">No transfer activity recorded yet.</div>';
      return;
   }

   container.innerHTML = logEntries.map(entry => `
      <div class="log-entry-card" data-id="${escHtml(entry.id)}">
         <div class="log-entry-left">
            <div class="log-details">
               <div class="log-title-row">
                  <span class="log-message">${escHtml(entry.details)}</span>
               </div>
            </div>
         </div>
         <div class="log-entry-right">
            <span class="log-time">${formatTime(entry.timestamp)}</span>
         </div>
      </div>
   `).join('');
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
               details: String(msg.details || '')
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
      details: String(msg.details || '')
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
      } catch (_) {
      }
      logEntries.length = 0;
      renderLogs();
   });
   void fetchLogs();
}

if (document.readyState === 'loading') {
   document.addEventListener('DOMContentLoaded', initLogControls);
} else {
   initLogControls();
}
