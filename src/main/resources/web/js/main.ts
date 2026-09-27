import {initNav} from './app.js';
import './storage.js';
import './state.js';

await import('./network.js');
initNav('section-network');

import('./identity.js').catch(e => console.error('[identity]', e));
import('./messages.js').catch(e => console.error('[messages]', e));

