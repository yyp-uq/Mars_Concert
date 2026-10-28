/* ════════════════════════════════════════════════════════════
   orders-loader.js
   订单 SPA 页逻辑：列表 / 状态筛选 / 详情 / 取消 / 过期倒计时
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  let currentStatus = '';
  let expiryTimer = null;

  async function init(rootEl) {
    const isAuth = !!localStorage.getItem('mars_access');
    const authArea = document.getElementById('authArea');
    if (!isAuth) {
      authArea.innerHTML = '<p style="color:#E63946;padding:1rem 0">请先 <a href="#/mission" style="color:#F4A261">登录</a> 后查看订单。</p>';
      document.getElementById('orderList').innerHTML = '';
      return;
    } else {
      authArea.innerHTML = '';
    }

    // 状态筛选按钮
    document.querySelectorAll('[data-status]').forEach(btn => {
      btn.onclick = () => {
        currentStatus = btn.dataset.status;
        loadList();
      };
    });

    await loadList();
    await loadTickets();
    startExpiryTick();
  }

  function destroy() {
    if (expiryTimer) { clearInterval(expiryTimer); expiryTimer = null; }
  }

  async function loadList() {
    const listEl = document.getElementById('orderList');
    listEl.innerHTML = '<div style="color:#aaa;padding:2rem;text-align:center">加载中…</div>';
    try {
      const query = { pageNum: 1, pageSize: 20 };
      if (currentStatus) query.status = currentStatus;
      const page = await MarsApi.get('/orders', query);
      const items = page.list || [];
      if (!items.length) {
        listEl.innerHTML = '<div style="color:#aaa;padding:2rem;text-align:center">暂无订单</div>';
        return;
      }
      listEl.innerHTML = items.map(o => renderOrder(o)).join('');
      bindListActions();
    } catch (e) { listEl.innerHTML = '<div style="color:#E63946;padding:2rem;text-align:center">加载失败：' + e.message + '</div>'; }
  }

  function renderOrder(o) {
    const isWait = o.status === 'WAIT_PAY';
    const payLabel = statusLabel(o.status);
    const expired = isWait && new Date(o.expireTime) < new Date();
    const remainHtml = isWait && !expired
      ? `<span style="color:#F4A261" data-expire="${o.expireTime}">倒计时中…</span>`
      : expired ? `<span style="color:#E63946">已过期</span>` : '';

    const cancelBtn = isWait && !expired
      ? `<button data-cancel="${o.orderNo}" style="padding:.4rem 1rem;border:1px solid #E63946;color:#E63946;background:transparent;border-radius:6px;margin-left:.5rem">取消订单</button>` : '';

    const itemsHtml = (o.items || []).map(i =>
      `<div style="display:flex;justify-content:space-between;padding:.25rem 0;color:#ccc;font-size:.85rem">
         <span>${i.tierName} × ${i.quantity}</span><span>¥${i.subTotal}</span>
       </div>`).join('');

    return `
      <div class="event-cell" style="padding:1.2rem 1.6rem;margin-bottom:.8rem;border:1px solid #222">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:.5rem">
          <strong>${o.eventTitle || '场次 #' + o.eventId}</strong>
          <span style="color:${statusColor(o.status)}">${payLabel} ${remainHtml}</span>
        </div>
        <div style="color:#888;font-size:.8rem;margin-bottom:.5rem">
          订单号：${o.orderNo} · 下单时间：${(o.createTime || '').replace('T', ' ').slice(0, 16)}
        </div>
        ${itemsHtml}
        <div style="border-top:1px dashed #333;margin-top:.5rem;padding-top:.5rem;display:flex;justify-content:space-between;align-items:center">
          <span style="font-weight:600">应付 ¥${o.payAmount}  (含服务费 ¥${o.serviceAmount})</span>
          <span>
            ${isWait && !expired ? `<button data-pay="${o.orderNo}" style="padding:.4rem 1rem;background:linear-gradient(135deg,#E63946,#F4A261);color:#fff;border:0;border-radius:6px">去支付</button>` : ''}
            ${cancelBtn}
          </span>
        </div>
      </div>`;
  }

  function bindListActions() {
    document.querySelectorAll('[data-cancel]').forEach(btn => {
      btn.onclick = async () => {
        const no = btn.dataset.cancel;
        if (!confirm('确定取消订单 ' + no + ' 吗？')) return;
        try {
          await MarsApi.post('/orders/' + no + '/cancel', null);
          document.getElementById('orderTip').textContent = '✓ 已取消，库存已回补';
          setTimeout(() => document.getElementById('orderTip').textContent = '', 2000);
          await loadList();
        } catch (e) { alert('取消失败：' + e.message); }
      };
    });
    document.querySelectorAll('[data-pay]').forEach(btn => {
      btn.onclick = async () => {
        const no = btn.dataset.pay;
        btn.disabled = true; btn.textContent = '跳转中…';
        try {
          // 发起支付 → 拿到模拟收银台URL → 新窗口打开
          const vo = await MarsApi.post('/payments/create', null, { query: { orderNo: no, channel: 'MOCK' } });
          window.open(vo.payUrl, '_blank');
        } catch (e) { alert('发起支付失败：' + e.message); }
        btn.disabled = false; btn.textContent = '去支付';
      };
    });
  }

  /* ── 电子票：支付成功后展示票与二维码 ─────────────────────── */
  async function loadTickets() {
    const area = document.getElementById('ticketArea');
    if (!area) return;
    try {
      const tickets = await MarsApi.get('/tickets');
      if (!tickets || !tickets.length) {
        area.innerHTML = '<div style="color:#aaa;padding:1.5rem;text-align:center">暂无电子票（支付成功后自动出票）</div>';
        return;
      }
      area.innerHTML = tickets.map(renderTicket).join('');
      // 二维码接口需要 Authorization，img 标签无法带头 → fetch 成 blob 加载
      tickets.forEach(t => loadQr(t.ticketNo));
    } catch (e) {
      area.innerHTML = '<div style="color:#E63946;padding:1.5rem;text-align:center">电子票加载失败：' + e.message + '</div>';
    }
  }

  function renderTicket(t) {
    return `
      <div class="event-cell" style="padding:1.2rem 1.6rem;margin-bottom:.8rem;border:1px solid #222;display:flex;gap:1.4rem;align-items:center">
        <div style="flex:0 0 150px;background:#111;border-radius:8px;padding:.6rem;text-align:center">
          <img id="qr-${t.ticketNo}" alt="二维码" style="width:120px;height:120px;display:block;margin:0 auto">
        </div>
        <div style="flex:1">
          <strong>${t.eventTitle || '场次 #' + t.eventId}</strong>
          <div style="color:#888;font-size:.8rem;margin:.3rem 0">
            票号：${t.ticketNo} · ${t.seatId ? '座位 #' + t.seatId : '站票'}
          </div>
          <span style="color:${t.status === 'VALID' ? '#4ECDC4' : '#aaa'}">${ticketStatusLabel(t.status)}</span>
          ${t.checkinTime ? `<div style="color:#888;font-size:.75rem;margin-top:.2rem">核销时间：${(t.checkinTime || '').replace('T', ' ').slice(0, 16)}</div>` : ''}
        </div>
      </div>`;
  }

  function ticketStatusLabel(s) {
    return { VALID: '有效', USED: '已核销', REFUNDED: '已退款' }[s] || s;
  }

  async function loadQr(ticketNo) {
    try {
      const resp = await fetch('/api/v1/tickets/' + ticketNo + '/qrcode', {
        headers: { 'Authorization': 'Bearer ' + (localStorage.getItem('mars_access') || '') }
      });
      if (!resp.ok) { return; }
      const blob = await resp.blob();
      const img = document.getElementById('qr-' + ticketNo);
      if (img) img.src = URL.createObjectURL(blob);
    } catch (e) { /* 单张二维码失败不影响其它票展示 */ }
  }

  function startExpiryTick() {
    if (expiryTimer) return;
    expiryTimer = setInterval(() => {
      document.querySelectorAll('[data-expire]').forEach(el => {
        const t = new Date(el.dataset.expire).getTime();
        const diff = t - Date.now();
        if (diff <= 0) { el.textContent = '已过期'; el.style.color = '#E63946'; return; }
        const m = Math.floor(diff / 60000);
        const s = Math.floor((diff % 60000) / 1000);
        el.textContent = `待支付 ${m}分${String(s).padStart(2,'0')}秒`;
      });
    }, 1000);
  }

  function statusLabel(s) {
    return { WAIT_PAY: '待支付', PAID: '已支付', TICKETED: '已出票', FINISHED: '已完成',
             CANCELLED: '已取消', REFUNDING: '退款中', REFUNDED: '已退款' }[s] || s;
  }
  function statusColor(s) {
    return { WAIT_PAY: '#F4A261', PAID: '#4ECDC4', TICKETED: '#4ECDC4',
             FINISHED: '#aaa', CANCELLED: '#E63946', REFUNDING: '#F4A261', REFUNDED: '#aaa' }[s] || '#ccc';
  }

  window.MarsViews = window.MarsViews || {};
  window.MarsViews.orders = { init, destroy };
})();
