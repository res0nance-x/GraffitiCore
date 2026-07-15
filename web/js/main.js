/**
 * Application entry point.
 * The primary identity seed is collected by a Swing dialog in Main.kt before
 * the server starts, so by the time this script runs the identity is guaranteed
 * to exist — no first-run check needed here.
 */
import { initNav } from './app.js';
await import('./network.js');
initNav('section-network');
import('./identity.js').catch(e => console.error('[identity]', e));
import('./peers.js').catch(e => console.error('[peers]', e));
import('./messages.js').catch(e => console.error('[messages]', e));
import('./storage.js').catch(e => console.error('[settings]', e));
// Fetch settings and display build number on startup
try {
    const res = await fetch('/api/settings');
    const settings = await res.json();
    if (settings.ok && settings.buildNumber) {
        document.title = `Graffiti (Build ${settings.buildNumber})`;
        const buildEl = document.getElementById('app-build-number');
        if (buildEl) {
            buildEl.textContent = `Build: ${settings.buildNumber}`;
        }
    }
}
catch (e) {
    console.error('Failed to load settings', e);
}
//# sourceMappingURL=main.js.map