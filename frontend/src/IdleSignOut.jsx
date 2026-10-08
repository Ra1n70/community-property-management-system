import React, { useEffect, useState } from 'react';
import { api, portalRole, portalLogin } from './api';

// Signs out after 60 minutes without mouse, keyboard or touch activity in any tab of this portal.
// Automatic polling does not count as activity. A warning appears during the last minute.
const IDLE_LIMIT_MS = 60 * 60 * 1000, WARNING_MS = 60 * 1000, KEEP_ALIVE_MS = 5 * 60 * 1000, SHARE_MS = 10 * 1000;
const storageKey = () => `cpms-last-activity-${portalRole()}`;
function readShared() { try { return Number(localStorage.getItem(storageKey())) || 0; } catch { return 0; } }
function writeShared(time) { try { localStorage.setItem(storageKey(), String(time)); } catch { /* this tab keeps its own time */ } }

export default function IdleSignOut() {
  const [secondsLeft, setSecondsLeft] = useState(null);
  useEffect(() => {
    let last = Date.now(), shared = 0, keptAlive = Date.now(), finished = false;
    function activity() {
      const now = Date.now(); last = now;
      if (now - shared > SHARE_MS) { shared = now; writeShared(now); }
      // Pages without polling make no requests while someone reads or types; keep the server session alive for them.
      if (now - keptAlive > KEEP_ALIVE_MS) { keptAlive = now; api('/auth/me').catch(() => {}); }
    }
    async function check() {
      if (finished) return;
      const idle = Date.now() - (readShared() || last);
      if (idle >= IDLE_LIMIT_MS) {
        finished = true;
        try { await api('/auth/logout', { method: 'POST' }); } catch { /* the session may already be gone */ }
        location.replace(portalLogin() + '#idle');
        return;
      }
      setSecondsLeft(idle >= IDLE_LIMIT_MS - WARNING_MS ? Math.ceil((IDLE_LIMIT_MS - idle) / 1000) : null);
    }
    // No 'scroll': pages scroll themselves when new messages arrive. Wheel, touch, keys and scrollbar clicks cover people.
    const events = ['mousemove', 'mousedown', 'click', 'keydown', 'wheel', 'touchstart'];
    activity();
    events.forEach(name => window.addEventListener(name, activity, { capture: true, passive: true }));
    document.addEventListener('visibilitychange', check);
    const timer = setInterval(check, 1000);
    return () => {
      finished = true; clearInterval(timer);
      events.forEach(name => window.removeEventListener(name, activity, { capture: true }));
      document.removeEventListener('visibilitychange', check);
    };
  }, []);
  if (secondsLeft === null) return null;
  return <div className="idle-warning" role="alertdialog" aria-labelledby="idle-warning-title">
    <strong id="idle-warning-title">Still there?</strong>
    <p>For your security you will be signed out in {secondsLeft} second{secondsLeft === 1 ? '' : 's'} because there has been no activity for almost 60 minutes.</p>
    <button type="button" className="primary" onClick={() => setSecondsLeft(null)}>Stay signed in</button>
  </div>;
}
