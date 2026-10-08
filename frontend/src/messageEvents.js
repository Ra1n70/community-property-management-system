import {useEffect, useRef, useState} from 'react';
import {portalUrl} from './api';

// One shared Server-Sent Events connection per page. The server sends {type, conversationId, messageId}
// when a message arrives or is read; pages then fetch fresh data through the normal API.
const listeners = new Set(), statusListeners = new Set();
let source = null, connected = false, retry = 0;

function setConnected(value) {
  if (connected === value) return;
  connected = value;
  statusListeners.forEach(listener => listener(value));
}

function open() {
  if (source || typeof EventSource !== 'function' || !listeners.size) return;
  source = new EventSource(portalUrl('/api/direct-messages/stream'));
  source.addEventListener('ready', () => setConnected(true));
  source.addEventListener('dm', event => {
    let data;
    try { data = JSON.parse(event.data); } catch { return; }
    listeners.forEach(listener => listener(data));
  });
  source.onerror = () => {
    setConnected(false);
    // The browser reconnects by itself after a dropped connection. A refused one (e.g. signed out) closes;
    // try again later in case the session comes back, while pages keep their slower fallback refresh.
    if (source.readyState === EventSource.CLOSED) {
      source = null;
      clearTimeout(retry);
      retry = setTimeout(open, 30000);
    }
  };
}

function close() {
  if (listeners.size) return;
  clearTimeout(retry);
  source?.close();
  source = null;
  setConnected(false);
}

/** Calls onEvent for every pushed message event while the component is mounted. */
export function useMessageEvents(onEvent) {
  const latest = useRef(onEvent);
  latest.current = onEvent;
  useEffect(() => {
    const listener = data => latest.current(data);
    listeners.add(listener);
    open();
    return () => { listeners.delete(listener); close(); };
  }, []);
}

/** True while the push connection is open; pages refresh less often then. */
export function useMessageStreamConnected() {
  const [value, setValue] = useState(connected);
  useEffect(() => {
    statusListeners.add(setValue);
    setValue(connected);
    return () => statusListeners.delete(setValue);
  }, []);
  return value;
}
