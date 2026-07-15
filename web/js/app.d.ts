/**
 * App shell — section routing, navigation, and WebSocket event bus.
 */
type SectionHandler = () => void | Promise<void>;
type WsEventHandler = (msg: Record<string, unknown>) => void | Promise<void>;
type WsOpenHandler = () => void | Promise<void>;
/**
 * Register a callback that runs when the WebSocket connection is opened.
 */
export declare function onWsOpen(fn: WsOpenHandler): void;
/**
 * Register a callback that runs every time the given section is shown.
 */
export declare function onSectionShow(id: string, fn: SectionHandler): void;
/**
 * Register a callback for a server-pushed WebSocket event name.
 * Multiple handlers can be registered for the same event.
 */
export declare function onWsEvent(event: string, fn: WsEventHandler): void;
/**
 * Show the section with the given id and hide all others.
 */
export declare function showSection(id: string): void;
/**
 * Attach click handlers to the nav links already in the HTML.
 */
export declare function initNav(defaultSection?: string): void;
export {};
