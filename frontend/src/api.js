// Portal selection follows this tab's URL, not shared localStorage or the last login.
// Each role has its own entry: /resident, /manager and /provider (sign-in at <entry>/login).
export const portalPaths = { RESIDENT: '/resident', MANAGER: '/manager', PROVIDER: '/provider' };
export function portalRole() {
  if (location.pathname.startsWith('/resident')) return 'RESIDENT';
  if (location.pathname.startsWith('/manager')) return 'MANAGER';
  if (location.pathname.startsWith('/provider')) return 'PROVIDER';
  const selected = new URLSearchParams(location.search).get('portal');
  return ['MANAGER', 'PROVIDER', 'RESIDENT'].includes(selected) ? selected : 'RESIDENT';
}
export function portalUrl(value) {
  const url = new URL(value, location.origin);
  if (url.origin !== location.origin) return value;
  url.searchParams.set('portal', portalRole());
  return url.pathname + url.search + url.hash;
}
export function portalHome(role = portalRole()) { return portalPaths[role]; }
export function portalLogin(role = portalRole()) { return `${portalPaths[role]}/login`; }
export function portalHeaders() { return {'X-Community-Portal': portalRole()}; }
// Retry reads once for a temporary connection/proxy failure. Never replay writes.
// options.signal lets the caller cancel a request it no longer needs (e.g. a search replaced by a newer one);
// a cancelled request rejects with an AbortError and is never retried.
export async function requestJson(url, {signal: cancel, ...options} = {}) {
  const reading = (options.method || 'GET') === 'GET';
  for (let attempt = 0; ; attempt++) {
    if (cancel?.aborted) throw cancelled();
    const controller = reading || cancel ? new AbortController() : null;
    const timer = reading ? setTimeout(() => controller.abort(), 10000) : null;
    const stop = () => controller.abort();
    cancel?.addEventListener('abort', stop);
    try {
      const response = await fetch(url, {...options, ...(controller ? {signal: controller.signal} : {})});
      if (reading && attempt === 0 && [502, 503, 504].includes(response.status)) {
        await response.body?.cancel();
        continue;
      }
      // Void endpoints (revoke links, change password) answer 200 with an empty body; only malformed JSON is an error.
      let result = null;
      const text = response.status === 204 ? '' : await response.text();
      if (text.trim()) {
        try { result = JSON.parse(text); }
        catch { const error = new Error('The server returned an invalid response. Please refresh and try again.'); error.status = response.status; throw error; }
      }
      if (!response.ok) { const error = new Error(result?.message || 'Request failed. Please try again.'); error.status = response.status; throw error; }
      return result;
    } catch (error) {
      if (cancel?.aborted) throw cancelled();
      const connection = error instanceof TypeError || error.name === 'AbortError';
      if (reading && attempt === 0 && connection) continue;
      if (connection) throw new Error('Cannot connect to the server. Please try again.');
      throw error;
    } finally { if (timer) clearTimeout(timer); cancel?.removeEventListener('abort', stop); }
  }
}
function cancelled() { return new DOMException('The request was cancelled.', 'AbortError'); }
export const isCancelled = error => error?.name === 'AbortError';
export async function refreshCsrf() {
  return requestJson('/api/auth/csrf', { credentials: 'same-origin', headers: portalHeaders() });
}
export async function api(path, { method = 'GET', data, form = false, signal } = {}) {
  const headers = portalHeaders();
  if (method !== 'GET') {
    const csrf = await refreshCsrf();
    headers[csrf.headerName] = csrf.token;
    if (!(data instanceof FormData)) headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json';
  }
  return requestJson(`/api${path}`, {
    method, headers, credentials: 'same-origin', signal,
    body: data ? (data instanceof FormData ? data : form ? new URLSearchParams(data) : JSON.stringify(data)) : undefined,
  });
}
