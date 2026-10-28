/* 统一的后端请求封装：自动拼前缀、带 token、401 自动刷新、解包 Result、统一错误提示 */
const BASE = '/api/v1';
let refreshing = null;   // 防并发刷新

function getAccess() { return localStorage.getItem('mars_access'); }
function getRefresh() { return localStorage.getItem('mars_refresh'); }
function saveTokens(d) {
  localStorage.setItem('mars_access', d.accessToken);
  localStorage.setItem('mars_refresh', d.refreshToken);
  localStorage.setItem('mars_uid', d.userId);
}

async function rawRequest(method, path, options = {}) {
  const { query, body, auth = true } = options;
  let url = BASE + path;
  if (query) {
    const qs = new URLSearchParams(
      Object.entries(query).filter(([, v]) => v !== undefined && v !== null && v !== '')
    ).toString();
    if (qs) url += '?' + qs;
  }
  const headers = { 'Accept': 'application/json' };
  if (auth && getAccess()) headers['Authorization'] = 'Bearer ' + getAccess();
  if (body) headers['Content-Type'] = 'application/json';
  return fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
}

async function request(method, path, options = {}) {
  let resp = await rawRequest(method, path, options);

  // access 过期 → 用 refresh 换新后重试一次
  if (resp.status === 401 && options.auth !== false && getRefresh() && !options._retry) {
    try {
      if (!refreshing) {
        refreshing = rawRequest('POST', '/auth/refresh', { body: { refreshToken: getRefresh() }, auth: false });
      }
      const r = await refreshing; refreshing = null;
      const j = await r.json();
      if (j.code === 0) { saveTokens(j.data); }
      else { throw new Error(j.message); }
    } catch (e) {
      localStorage.clear();
      if (!location.pathname.endsWith('login.html')) location.href = '/pages/login.html';
      throw e;
    }
    options._retry = true;
    resp = await rawRequest(method, path, options);
  }

  let json;
  try { json = await resp.json(); }
  catch { throw new Error('HTTP ' + resp.status); }
  if (resp.status === 403) throw new Error('没有权限执行该操作');
  if (json.code !== 0) throw new Error(json.message || '请求失败');
  return json.data;
}

window.MarsApi = {
  get:  (p, query, opts) => request('GET', p, { ...opts, query }),
  post: (p, body, opts)  => request('POST', p, { ...opts, body }),
  put:  (p, body, opts)  => request('PUT', p, { ...opts, body }),
  del:  (p, opts)        => request('DELETE', p, opts),
  saveTokens
};
