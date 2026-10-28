/* ════════════════════════════════════════════════════════════
   tickets-loader.js
   选票 SPA 页逻辑：拉场次列表 + 选详情 + 票数 + 加购 / 下单
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  let eventList = [];
  let selectedEventId = null;
  let selected = {};   // tierId -> qty

  async function init(rootEl) {
    const isAuth = !!localStorage.getItem('mars_access');
    document.getElementById('authTip').textContent = isAuth ? '' : '（未登录将无法加购或下单）';

    // 1) 拉场次列表（公开）
    try {
      const page = await MarsApi.get('/events', { pageNum: 1, pageSize: 20 }, { auth: false });
      eventList = page.list || [];
      const sel = document.getElementById('eventSelect');
      sel.innerHTML = eventList.map(e =>
        `<option value="${e.id}">${e.title} · ${(e.startTime || '').slice(0, 16).replace('T', ' ')}</option>`
      ).join('');
      if (eventList.length) {
        selectedEventId = eventList[0].id;
        await loadDetail(selectedEventId);
      }
    } catch (e) { document.getElementById('tierList').innerHTML = '<div class="event-cell" style="padding:2rem;text-align:center;color:#E63946">场次加载失败</div>'; }

    // 2) 事件绑定
    document.getElementById('eventSelect').onchange = async (e) => {
      selectedEventId = Number(e.target.value);
      selected = {};
      await loadDetail(selectedEventId);
    };
    document.getElementById('cartBtn').onclick = addToCart;
    document.getElementById('buyBtn').onclick = buyNow;
    document.getElementById('cartViewBtn').onclick = () => location.hash = '#/orders';
  }

  async function loadDetail(id) {
    try {
      const ev = await MarsApi.get('/events/' + id, null, { auth: false });
      const list = document.getElementById('tierList');
      if (!ev.tiers || !ev.tiers.length) {
        list.innerHTML = '<div class="event-cell" style="padding:2rem;text-align:center;color:#aaa">本场暂无可售票档</div>';
        return;
      }
      list.innerHTML = ev.tiers.map(t => {
        const remain = t.remainStock;
        const disabled = remain <= 0;
        return `
          <div class="event-cell" data-tier="${t.tierId}" style="${disabled ? 'opacity:.4' : ''}">
            <span class="event-cell__label">${t.name}</span>
            <span class="event-cell__value">¥${t.price}</span>
            <span class="event-cell__sub">余票 ${remain}</span>
            <div style="display:flex;gap:.4rem;margin-top:.5rem;align-items:center">
              <button data-act="dec" style="padding:.2rem .7rem;border:1px solid #333;background:#111;color:#fff;border-radius:4px">-</button>
              <span data-qty="${t.tierId}" style="min-width:1.5rem;text-align:center;font-weight:600">${selected[t.tierId] || 0}</span>
              <button data-act="inc" ${disabled ? 'disabled' : ''} style="padding:.2rem .7rem;border:1px solid #333;background:#111;color:#fff;border-radius:4px">+</button>
            </div>
          </div>`;
      }).join('');

      list.querySelectorAll('.event-cell').forEach(cell => {
        const tid = Number(cell.dataset.tier);
        cell.querySelector('[data-act=dec]').onclick = () => {
          if (!selected[tid]) return;
          selected[tid] = Math.max(0, selected[tid] - 1);
          cell.querySelector('[data-qty]').textContent = selected[tid];
        };
        cell.querySelector('[data-act=inc]').onclick = () => {
          const t = ev.tiers.find(x => x.tierId === tid);
          const cur = selected[tid] || 0;
          if (cur >= t.remainStock) return flashTip('余票不足');
          selected[tid] = cur + 1;
          cell.querySelector('[data-qty]').textContent = selected[tid];
        };
      });
    } catch (e) { document.getElementById('tierList').innerHTML = '<div style="padding:2rem;color:#E63946">详情加载失败</div>'; }
  }

  function selectedItems() {
    return Object.entries(selected)
      .filter(([, q]) => q > 0)
      .map(([tierId, qty]) => ({ tierId: Number(tierId), quantity: qty }));
  }

  async function addToCart() {
    const items = selectedItems();
    if (!items.length) return flashTip('请先选票');
    const tip = document.getElementById('ticketTip');
    tip.textContent = '加入购物车中…';
    try {
      for (const it of items) {
        await MarsApi.post('/cart/add', null, { query: { eventId: selectedEventId, tierId: it.tierId, qty: it.quantity } });
      }
      tip.textContent = '✓ 已加入购物车';
      setTimeout(() => tip.textContent = '', 2000);
    } catch (e) { tip.textContent = '加入失败：' + e.message; }
  }

  async function buyNow() {
    const items = selectedItems();
    if (!items.length) return flashTip('请先选票');
    if (!localStorage.getItem('mars_access')) {
      flashTip('请先登录（右上角 login 页面）');
      return;
    }
    const tip = document.getElementById('ticketTip');
    tip.textContent = '下单中…';
    try {
      const orderNo = await MarsApi.post('/orders', {
        eventId: selectedEventId,
        idempotentKey: crypto.randomUUID(),
        items
      });
      tip.textContent = '✓ 下单成功！正在跳转订单页…';
      setTimeout(() => { location.hash = '#/orders'; }, 800);
    } catch (e) { tip.textContent = '下单失败：' + e.message; }
  }

  function flashTip(msg) {
    const tip = document.getElementById('ticketTip');
    tip.textContent = msg;
    setTimeout(() => tip.textContent = '', 2500);
  }

  window.MarsViews = window.MarsViews || {};
  window.MarsViews.tickets = { init };
})();
