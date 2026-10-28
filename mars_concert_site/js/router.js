/* ════════════════════════════════════════════════════════════
   MARS CONCERT 2.0 · router.js
   SPA hash 路由：#/ | #/setlist | #/mission | #/artist
   核心原则：视图切换只显隐/挂载 DOM，audio 常驻 shell 层，
   导航过程中绝不触碰播放引擎 → 物理上零断顿。
   片段 DOM 缓存复用：事件绑定存活，init 幂等，destroy 只清定时器。
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  const appView = document.getElementById('appView');
  const homeEl = document.getElementById('view-home');
  const HOME_TITLE = document.title;

  if ('scrollRestoration' in history) history.scrollRestoration = 'manual';

  /* ─── 路由表 ──────────────────────────────────────────────── */
  // def: { url, title, init(el), onShow(el), destroy() }
  const routes = {
    home: {
      title: HOME_TITLE,
      // scroll-video 在页面加载时已自动 init，此处无需重复 init
      onShow() {
        if (window.MarsScrollVideo) window.MarsScrollVideo.init();
        if (window.MarsParticles) window.MarsParticles.resume();
      },
      destroy() {
        if (window.MarsScrollVideo) window.MarsScrollVideo.destroy();
        if (window.MarsParticles) window.MarsParticles.pause();
        // 隐藏首页时暂停所有 loop 视频（observer 已断开，不会再自动播）
        homeEl.querySelectorAll('video').forEach(v => { try { v.pause(); } catch (e) {} });
      }
    },
    setlist: {
      url: 'views/setlist.html',
      title: 'Twin Setlist · MARS CONCERT 2.0',
      init(el) { if (window.MarsViews.setlist) window.MarsViews.setlist.init(el); },
      onShow() { if (window.MarsViews.setlist) window.MarsViews.setlist.onShow(); },
      destroy() { if (window.MarsViews.setlist) window.MarsViews.setlist.destroy(); }
    },
    mission: {
      url: 'views/mission.html',
      title: 'Mission Control · MARS CONCERT 2.0',
      // functional.init 幂等（事件绑一次，计时器可重启）→ 每次显示都调用
      onShow(el) { if (window.MarsViews.functional) window.MarsViews.functional.init(el); },
      destroy() { if (window.MarsViews.functional) window.MarsViews.functional.destroy(); }
    },
    artist: {
      url: 'views/artist.html',
      title: 'The Architect · MARS CONCERT 2.0',
      onShow(el) { if (window.MarsViews.functional) window.MarsViews.functional.init(el); },
      destroy() { if (window.MarsViews.functional) window.MarsViews.functional.destroy(); }
    }
  };

  const wrappers = {};  // name -> 缓存的视图片段 wrapper（仅子页）
  const inited = { home: true }; // home 已随页面加载初始化

  let current = null;
  let navToken = 0;   // 防快速连续导航的异步竞态

  /* ─── Hash 解析 ───────────────────────────────────────────── */
  function parseHash() {
    const h = location.hash.replace(/^#\/?/, '').split('?')[0].replace(/\/$/, '');
    return routes[h] ? h : 'home';
  }

  /* ─── 片段加载（仅首次，之后缓存复用）───────────────────────── */
  async function ensureLoaded(name) {
    if (wrappers[name]) return wrappers[name];
    const res = await fetch(routes[name].url, { cache: 'no-cache' });
    if (!res.ok) throw new Error('[router] view load failed: ' + routes[name].url + ' (' + res.status + ')');
    const html = await res.text();
    const wrap = document.createElement('div');
    wrap.className = 'view';
    wrap.id = 'view-' + name;
    wrap.innerHTML = html;
    wrappers[name] = wrap;
    return wrap;
  }

  /* ─── 外壳 chrome 切换：scroll-snap 作用域 / 固定 UI 显隐 / 子页背景 ── */
  function setChrome(name) {
    const htmlEl = document.documentElement;
    if (name === 'home') {
      htmlEl.classList.remove('subpage');
      htmlEl.removeAttribute('data-route');
      document.body.classList.remove('page-body');
    } else {
      htmlEl.classList.add('subpage');
      htmlEl.setAttribute('data-route', name);
      document.body.classList.add('page-body');
    }
  }

  /* ─── 导航主流程（绝不触碰 audio / MarsPlayer）──────────────── */
  async function go(name) {
    if (!routes[name]) name = 'home';
    if (name === current) return;
    const token = ++navToken;

    const prev = current;
    current = name; // 先更新：nav.js 等滚轮/键盘门控立即生效

    // 1) 卸载旧视图（销毁 ScrollTrigger/observer/计时器，不动 audio）
    if (prev) {
      const p = routes[prev];
      if (p.destroy) { try { p.destroy(); } catch (e) { console.error('[router] destroy ' + prev, e); } }
      const pEl = prev === 'home' ? homeEl : wrappers[prev];
      if (pEl) pEl.style.display = 'none';
    }

    // 2) 准备目标视图（首次 fetch，之后 appendChild 复用缓存 DOM）
    let el;
    if (name === 'home') {
      el = homeEl;
    } else {
      try {
        el = await ensureLoaded(name);
      } catch (err) {
        console.error(err);
        current = prev; // 加载失败回滚
        return;
      }
    }
    if (token !== navToken) return; // 已被更新的导航取代

    if (!el.parentNode) appView.appendChild(el);
    el.style.display = '';

    // 3) chrome / 滚动位置 / 标题
    setChrome(name);
    window.scrollTo(0, 0);
    document.title = routes[name].title;

    // 4) 生命周期
    const def = routes[name];
    if (!inited[name]) {
      inited[name] = true;
      if (def.init) { try { def.init(el); } catch (e) { console.error('[router] init ' + name, e); } }
    }
    if (def.onShow) { try { def.onShow(el); } catch (e) { console.error('[router] onShow ' + name, e); } }
  }

  /* ─── hashchange 驱动 ─────────────────────────────────────── */
  window.addEventListener('hashchange', () => go(parseHash()));

  /* ─── 对外 API ────────────────────────────────────────────── */
  window.MarsRouter = {
    go(name) {
      const target = '#/' + (name === 'home' ? '' : name);
      if (location.hash === target || (name === 'home' && (location.hash === '' || location.hash === '#'))) {
        go(name); // hash 不变时手动触发
      } else {
        location.hash = target; // hashchange 会接管
      }
    },
    get current() { return current; }
  };

  /* ─── 初始路由解析 ────────────────────────────────────────── */
  // 首页 DOM 默认可见；若初始 hash 指向子页，需走完整卸载流程
  const initial = parseHash();
  if (initial === 'home') {
    current = 'home';
    setChrome('home');
    document.title = routes.home.title;
  } else {
    current = 'home'; // 让 go() 把 home 当旧视图正确卸载
    go(initial);
  }
})();
