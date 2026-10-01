const userIdKey = 'tm-user-id';

export function getUserId() {
  let id = localStorage.getItem(userIdKey);
  if (!id) {
    id = `user-${crypto.randomUUID().slice(0, 8)}`;
    localStorage.setItem(userIdKey, id);
  }
  return id;
}

export function getSessionId(eventId) {
  const key = `tm-session-${eventId}`;
  let s = localStorage.getItem(key);
  if (!s) {
    s = `sess-${crypto.randomUUID()}`;
    localStorage.setItem(key, s);
  }
  return s;
}

export class ApiError extends Error {
  constructor(status, code, message, body) {
    super(message || code || `HTTP ${status}`);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

async function request(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': getUserId(),
      ...(options.headers || {}),
    },
  });
  if (res.status === 204) return null;
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new ApiError(res.status, body?.code || body?.title, body?.detail, body);
  }
  return body;
}

export const api = {
  get: (p) => request(p),
  post: (p, data) => request(p, { method: 'POST', body: JSON.stringify(data ?? {}) }),
  put: (p, data) => request(p, { method: 'PUT', body: JSON.stringify(data ?? {}) }),
};

/** POST with an explicit waiting-room session header. */
export function postWithSession(path, data, sessionId) {
  return request(path, { method: 'POST', headers: { 'X-Session-Id': sessionId }, body: JSON.stringify(data) });
}

export function money(cents) {
  return `$${(cents / 100).toFixed(2)}`;
}

export function fmtDate(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}
