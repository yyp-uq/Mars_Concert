/* 右上角账号入口：未登录→跳登录页；已登录→弹出菜单（订单/票夹/退出） */
(function () {
  var btn = document.getElementById('userEntry');
  var menu = document.getElementById('userEntryMenu');
  if (!btn || !menu) return;

  function logged() { return !!localStorage.getItem('mars_access'); }

  function closeMenu() { menu.style.display = 'none'; }

  btn.addEventListener('click', function (e) {
    e.stopPropagation();
    if (!logged()) { location.href = '/pages/login.html'; return; }
    menu.style.display = (menu.style.display === 'none' || !menu.style.display) ? 'block' : 'none';
  });

  document.addEventListener('click', closeMenu);

  menu.addEventListener('click', function (e) {
    var act = e.target.getAttribute('data-act');
    if (!act) return;
    e.stopPropagation();
    closeMenu();
    if (act === 'orders') { location.hash = '#/orders'; return; }
    if (act === 'tickets') { location.hash = '#/tickets'; return; }
    if (act === 'logout') {
      // 通知后端吊销 token（失败也无所谓），再清本地登录态
      if (window.MarsApi) { MarsApi.post('/auth/logout', {}).catch(function () {}); }
      localStorage.removeItem('mars_access');
      localStorage.removeItem('mars_refresh');
      localStorage.removeItem('mars_uid');
      location.reload();
    }
  });

  // 登录态决定入口样式（金色描边=已登录）
  function render() { btn.classList.toggle('is-login', logged()); }
  window.addEventListener('storage', render);
  render();
})();
