import {initNav} from './app.js';

await import('./network.js');
initNav('section-network');

import('./identity.js').catch(e => console.error('[identity]', e));
import('./peers.js').catch(e => console.error('[peers]', e));
import('./messages.js').catch(e => console.error('[messages]', e));
import('./storage.js').catch(e => console.error('[settings]', e));
