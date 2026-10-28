/* ════════════════════════════════════════════════════════════
   mission-loader.js
   Mission Control 动态化：拉 /events/home 聚合接口，
   填倒计时目标 window.__MARS_TARGET__ + 场次列表 + 票价区间。
   挂载到 window.MarsViews.mission ，由 router.js 在 mission 页 onShow 时调用。
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  let inited = false;

  async function init(rootEl) {
    if (!rootEl) return;
    // 每次显示都重新拉一次（后端 home 数据变了也能刷新）
    try {
      const home = await MarsApi.get('/events/home', null, { auth: false });

      // 1) 给 functional.js 的倒计时注入目标
      if (home.nextEventTime) window.__MARS_TARGET__ = home.nextEventTime;

      // 2) 渲染场次列表
      const rows = document.getElementById('eventRows');
      if (rows && home.events && home.events.length) {
        rows.innerHTML = home.events.map(e => `
          <div class="event-cell">
            <span class="event-cell__label">${e.title || '演出场次'}</span>
            <span class="event-cell__value">${(e.startTime || '').replace('T', ' ').slice(0, 16)}</span>
            <span class="event-cell__sub">${e.city || ''}${e.city && e.guest ? ' · ' : ''}${e.guest || ''}</span>
          </div>`).join('');
      }

      // 3) 票价区间
      const priceEl = document.getElementById('priceRange');
      if (priceEl && home.priceRange) priceEl.textContent = home.priceRange;

      // 4) 场馆信息（替换 venue card — 若前端要展示）
      if (home.venue) {
        const subEl = rows ? rows.querySelector('.event-cell__sub') : null;
        // 场馆名挂到第一个场次的 sub 后面即可，不额外加 DOM
      }

      // 5) 重启 functional 计时器让新 target 生效
      if (window.MarsViews.functional) {
        // destroy + init = 重启 rAF/interval，新的 __MARS_TARGET__ 会被读到
        window.MarsViews.functional.destroy();
        window.MarsViews.functional.init(rootEl);
      }
    } catch (e) {
      console.error('[mission-loader] home 接口加载失败:', e);
    }
    inited = true;
  }

  window.MarsViews = window.MarsViews || {};
  window.MarsViews.mission = { init };
})();
