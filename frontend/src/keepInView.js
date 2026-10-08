/**
 * Scrolls a record opened from Search into view and keeps it there while sections above it finish loading
 * (their height changes would otherwise push it away). Stops after `ms` or as soon as the person scrolls.
 */
export function keepInView(find, { block = 'center', ms = 3000 } = {}) {
  let stopped = false, timer = 0;
  // A short timer instead of requestAnimationFrame, which does not run while the tab is in the background.
  const scroll = () => {
    if (stopped) return;
    clearTimeout(timer);
    timer = setTimeout(() => { if (!stopped) find()?.scrollIntoView({ block }); }, 30);
  };
  const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(scroll) : null;
  const stop = () => {
    stopped = true;
    observer?.disconnect();
    ['wheel', 'touchstart', 'keydown'].forEach(name => window.removeEventListener(name, stop, true));
  };
  observer?.observe(document.body);
  ['wheel', 'touchstart', 'keydown'].forEach(name => window.addEventListener(name, stop, true));
  scroll();
  setTimeout(stop, ms);
  return stop;
}
