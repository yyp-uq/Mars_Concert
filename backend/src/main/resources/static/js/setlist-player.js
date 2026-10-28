/* ════════════════════════════════════════════════════════════
   MARS CONCERT · Setlist 视图模块（登录 + 底部播放条 UI 控制器）
   登录弹窗 → 解锁歌单 → 点击播放（黑胶/VIP 会员可解锁 VIP 曲目）
   播放引擎由 player-core.js 统一持有，本模块只订阅事件渲染
   生命周期：init(rootEl) 一次绑定 / onShow() 状态同步 / destroy() 清定时器
   ════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  // 本地网易云 API 代理
  var PROXY_BASE = 'http://localhost:3000';
  var STORAGE_KEY = 'mars_netease_login';

  /* ─── DOM 元素引用（init 时赋值）────────────────────────── */
  var rootEl = null;
  var loginOverlay, loginModal, loginClose, loginGuest, loginSubmit, loginSpinner;
  var loginPhone, loginPassword, loginHint, loginBadge, loginBadgeText;
  var loginTabs, panelPhone, panelQr, qrImage;
  var loginSubtabs, subpanelPassword, subpanelSms, smsPhone, smsCode, codeBtn, smsSubmit, smsSpinner;
  var player, playerTitle, playerSubtitle, playerPlay, playIcon, playerPrev, playerNext, playerClose;
  var playerProgressTrack, playerProgressFill, playerCurrentTime, playerDuration;
  var tracks = [];
  var colBodies = [];

  /* ─── 模块状态 ──────────────────────────────────────────── */
  var isLoggedIn = false;
  var viewIndex = -1;
  var viewPlaying = false;
  var initialized = false;
  var QR_POLL_INTERVAL = null;
  var codeCountdown = null;

  var PLAY_ICON = '<path d="M8 5v14l11-7-11-7z" fill="currentColor"/>';
  var PAUSE_ICON = '<path d="M6 4h4v16H6V4zm8 0h4v16h-4V4z" fill="currentColor"/>';

  function $(id) { return rootEl.querySelector('#' + id); }

  /* ─── 会话存取（含网易云 cookie，用于 VIP 曲目解锁）───────── */
  function getSession() {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY));
    } catch (e) {
      return null;
    }
  }

  function saveSession(session) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } catch (e) {}
  }

  function clearSession() {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch (e) {}
  }

  /* ─── 会员等级标签（vipType: 110=黑胶, 100=普通会员, 11=老版黑胶） */
  function getVipLabel(vipType) {
    if (!vipType || vipType <= 0) return null;
    if (vipType >= 110) return '黑胶VIP';
    if (vipType >= 100) return '普通会员';
    if (vipType >= 11) return '黑胶VIP';
    return 'VIP';
  }

  /* ════════════════════════════════════════════════════════════
     登录模块
     ════════════════════════════════════════════════════════════ */

  function initLogin() {
    var saved = getSession();

    if (saved && saved.logged_in) {
      // 已登录，直接解锁（异步校验 cookie 有效性并刷新会员信息）
      isLoggedIn = true;
      unlockTracks();
      showBadge(saved.nickname || '已登录', saved.vipType);
      if (saved.cookie) refreshLoginStatus();
    } else {
      // 未登录，弹出登录窗
      showLoginOverlay();
      lockTracks();
    }
  }

  /* ─── 校验本地 cookie 有效性，同步昵称与会员状态 ──────────── */
  async function refreshLoginStatus() {
    var session = getSession();
    if (!session || !session.cookie) return;

    try {
      var url = PROXY_BASE + '/login/status?timestamp=' + Date.now() + '&cookie=' + encodeURIComponent(session.cookie);
      var res = await fetch(url);
      var data = await res.json();
      var inner = data && data.data;
      var profile = inner && inner.profile;

      if (profile) {
        session.nickname = profile.nickname || session.nickname;
        session.vipType = profile.vipType || 0;
        saveSession(session);
        showBadge(session.nickname, session.vipType);
      } else if (inner && inner.code === 301) {
        clearSession();
        isLoggedIn = false;
        lockTracks();
        showLoginOverlay();
        showLoginHint('登录已过期，请重新登录', 'error');
      }
    } catch (e) {
      // 代理未启动，沿用本地缓存会话
    }
  }

  function showLoginOverlay() {
    loginOverlay.classList.add('is-visible');
  }

  function hideLoginOverlay() {
    loginOverlay.classList.remove('is-visible');
  }

  function lockTracks() {
    colBodies.forEach(function (body) {
      body.classList.add('is-locked');
    });
  }

  function unlockTracks() {
    colBodies.forEach(function (body) {
      body.classList.remove('is-locked');
    });
  }

  function showBadge(name, vipType) {
    var label = getVipLabel(vipType);
    loginBadgeText.textContent = name + (label ? ' · ' + label : ' · NetEase');
    loginBadge.style.display = 'flex';
    setTimeout(function () {
      loginBadge.classList.add('is-visible');
    }, 50);
  }

  /* ─── Tab 切换 ────────────────────────────────────────────── */
  function bindLoginTabs() {
    loginTabs.addEventListener('click', function (e) {
      var btn = e.target.closest('.login-tabs__btn');
      if (!btn) return;

      loginTabs.querySelectorAll('.login-tabs__btn').forEach(function (b) {
        b.classList.remove('is-active');
      });
      btn.classList.add('is-active');

      var tab = btn.dataset.tab;
      panelPhone.classList.toggle('is-active', tab === 'phone');
      panelQr.classList.toggle('is-active', tab === 'qr');

      if (tab === 'qr' && !qrImage.dataset.loaded) {
        generateQR();
      }
    });
  }

  /* ─── 手机号登录 ──────────────────────────────────────────── */
  function handlePhoneLogin() {
    var phone = loginPhone.value.trim();
    var password = loginPassword.value;

    if (!phone || phone.length < 11) {
      showLoginHint('请输入正确的手机号', 'error');
      return;
    }
    if (!password) {
      showLoginHint('请输入密码', 'error');
      return;
    }

    loginSubmit.classList.add('is-loading');
    showLoginHint('正在登录...', '');

    attemptNetEaseLogin(phone, password).then(function (result) {
      loginSubmit.classList.remove('is-loading');

      if (result && result.success) {
        var nickname = result.nickname || '火星人';
        completeLogin(nickname, result.cookie, result.vipType);
      } else if (result && result.fallback) {
        showLoginHint('本地代理未启动，已进入游客模式（启动 netease-proxy 可真登录）', 'error');
        completeLogin('游客');
      } else {
        showLoginHint((result && result.msg) ? result.msg : '账号或密码错误', 'error');
      }
    }).catch(function () {
      loginSubmit.classList.remove('is-loading');
      showLoginHint('网络异常，请确认本地代理已启动（node app.js）', 'error');
    });
  }

  async function attemptNetEaseLogin(phone, password) {
    try {
      var url = PROXY_BASE + '/login/cellphone?phone=' + encodeURIComponent(phone) + '&password=' + encodeURIComponent(password) + '&timestamp=' + Date.now();
      var controller = new AbortController();
      var timeout = setTimeout(function () { controller.abort(); }, 8000);
      var res = await fetch(url, { method: 'GET', signal: controller.signal, credentials: 'include' });
      clearTimeout(timeout);
      var data = await res.json();

      if (data.code === 200 && data.account) {
        return {
          success: true,
          nickname: data.profile && data.profile.nickname ? data.profile.nickname : '火星人',
          uid: data.account.id,
          cookie: data.cookie || null,
          vipType: (data.profile && data.profile.vipType) || 0
        };
      }
      return { success: false, msg: data.message || data.msg || '登录失败' };
    } catch (e) {
      console.warn('[MARS Player] login failed:', e.message);
      return { success: false, fallback: true };
    }
  }

  /* ─── 子切换：密码 / 验证码 ────────────────────────────────── */
  function bindLoginSubtabs() {
    loginSubtabs.addEventListener('click', function (e) {
      var btn = e.target.closest('.login-subtabs__btn');
      if (!btn) return;

      loginSubtabs.querySelectorAll('.login-subtabs__btn').forEach(function (b) {
        b.classList.remove('is-active');
      });
      btn.classList.add('is-active');

      var subtab = btn.dataset.subtab;
      subpanelPassword.classList.toggle('is-active', subtab === 'password');
      subpanelSms.classList.toggle('is-active', subtab === 'sms');
    });
  }

  /* ─── 发送验证码 ──────────────────────────────────────────── */
  function handleSendCode() {
    var phone = smsPhone.value.trim();

    if (!phone || phone.length < 11) {
      showLoginHint('请输入正确的手机号', 'error');
      return;
    }

    codeBtn.disabled = true;
    codeBtn.textContent = '发送中...';
    showLoginHint('正在发送验证码，请稍候...', '');

    attemptSendCode(phone).then(function (result) {
      if (result && result.success) {
        showLoginHint('验证码已发送至 ' + phone + '，请查收短信', 'success');
        startCodeCountdown(60);
      } else if (result && result.fallback) {
        showLoginHint('本地代理未启动，请先运行 netease-proxy（node app.js）', 'error');
        codeBtn.disabled = false;
        codeBtn.textContent = '获取验证码';
      } else {
        showLoginHint((result && result.msg) ? result.msg : '发送失败，请稍后再试', 'error');
        codeBtn.disabled = false;
        codeBtn.textContent = '重新获取';
      }
    }).catch(function () {
      showLoginHint('网络异常，请确认本地代理已启动（node app.js）', 'error');
      codeBtn.disabled = false;
      codeBtn.textContent = '重新获取';
    });
  }

  async function attemptSendCode(phone) {
    try {
      var ts = Date.now();
      var url = PROXY_BASE + '/captcha/sent?phone=' + encodeURIComponent(phone) + '&timestamp=' + ts;
      var controller = new AbortController();
      var timeout = setTimeout(function () { controller.abort(); }, 8000);
      var res = await fetch(url, { method: 'GET', signal: controller.signal });
      clearTimeout(timeout);
      var data = await res.json();
      if (data.code === 200) return { success: true };
      return { success: false, msg: data.message || data.msg || '发送失败' };
    } catch (e) {
      console.warn('[MARS Player] send code failed:', e.message);
      return { success: false, fallback: true };
    }
  }

  function startCodeCountdown(seconds) {
    if (codeCountdown) clearInterval(codeCountdown);

    codeBtn.disabled = true;
    codeBtn.classList.add('is-counting');
    var remaining = seconds;

    codeBtn.textContent = remaining + 's 后重发';

    codeCountdown = setInterval(function () {
      remaining--;
      if (remaining <= 0) {
        clearInterval(codeCountdown);
        codeCountdown = null;
        codeBtn.disabled = false;
        codeBtn.classList.remove('is-counting');
        codeBtn.textContent = '获取验证码';
      } else {
        codeBtn.textContent = remaining + 's 后重发';
      }
    }, 1000);
  }

  /* ─── 验证码登录 ──────────────────────────────────────────── */
  function handleSmsLogin() {
    var phone = smsPhone.value.trim();
    var code = smsCode.value.trim();

    if (!phone || phone.length < 11) {
      showLoginHint('请输入正确的手机号', 'error');
      return;
    }
    if (!code || code.length < 4) {
      showLoginHint('请输入验证码', 'error');
      return;
    }

    smsSubmit.classList.add('is-loading');
    showLoginHint('正在登录...', '');

    attemptSmsLogin(phone, code).then(function (result) {
      smsSubmit.classList.remove('is-loading');

      if (result && result.success) {
        var nick = result.nickname || '火星人';
        completeLogin(nick, result.cookie, result.vipType);
      } else if (result && result.fallback) {
        showLoginHint('本地代理未启动，已进入游客模式（启动 netease-proxy 可真登录）', 'error');
        completeLogin('游客');
      } else {
        showLoginHint((result && result.msg) ? result.msg : '验证码错误或已过期', 'error');
      }
    }).catch(function () {
      smsSubmit.classList.remove('is-loading');
      showLoginHint('网络异常，请确认本地代理已启动（node app.js）', 'error');
    });
  }

  async function attemptSmsLogin(phone, code) {
    try {
      var url = PROXY_BASE + '/login/cellphone?phone=' + encodeURIComponent(phone) + '&captcha=' + encodeURIComponent(code) + '&timestamp=' + Date.now();
      var controller = new AbortController();
      var timeout = setTimeout(function () { controller.abort(); }, 8000);
      var res = await fetch(url, { method: 'GET', signal: controller.signal, credentials: 'include' });
      clearTimeout(timeout);
      var data = await res.json();
      if (data.code === 200 && data.account) {
        return {
          success: true,
          nickname: data.profile && data.profile.nickname ? data.profile.nickname : '火星人',
          uid: data.account.id,
          cookie: data.cookie || null,
          vipType: (data.profile && data.profile.vipType) || 0
        };
      }
      return { success: false, msg: data.message || data.msg || '验证码错误' };
    } catch (e) {
      console.warn('[MARS Player] sms login failed:', e.message);
      return { success: false, fallback: true };
    }
  }

  /* ─── 完成登录流程（cookie 落地，VIP 曲目解锁的关键）──────── */
  function completeLogin(nickname, cookie, vipType) {
    isLoggedIn = true;
    saveSession({
      logged_in: true,
      nickname: nickname,
      cookie: cookie || null,
      vipType: vipType || 0,
      timestamp: Date.now()
    });

    unlockTracks();
    showBadge(nickname, vipType);

    if (cookie) {
      var label = getVipLabel(vipType);
      showLoginHint(
        label
          ? '欢迎回来，' + nickname + '（' + label + '）· VIP 曲目可播放'
          : '欢迎回来，' + nickname + ' · 当前账号无会员，VIP 曲目无法播放',
        'success'
      );
    }

    // 登录后同步播放条（core 可能正携带首页带过来的播放态）
    syncPlayerFromCore();

    setTimeout(function () {
      hideLoginOverlay();
    }, 1200);
  }

  /* ─── 游客模式 ────────────────────────────────────────────── */
  function handleGuestMode() {
    isLoggedIn = true;
    showLoginHint('已进入游客模式', 'success');
    unlockTracks();
    showBadge('游客');
    syncPlayerFromCore();

    setTimeout(function () {
      hideLoginOverlay();
    }, 500);
  }

  /* ─── 生成二维码（加密接口，返回 base64 图）──────────────── */
  async function generateQR() {
    qrImage.innerHTML = '<span style="font-size:12px;color:var(--color-text-dim)">二维码生成中...</span>';

    try {
      var keyRes = await fetch(PROXY_BASE + '/login/qr/key?timestamp=' + Date.now());
      var keyData = await keyRes.json();
      if (keyData.code !== 200 || !keyData.data || !keyData.data.unikey) {
        throw new Error('key failed');
      }
      var unikey = keyData.data.unikey;

      var createRes = await fetch(PROXY_BASE + '/login/qr/create?key=' + encodeURIComponent(unikey) + '&qrimg=true&timestamp=' + Date.now());
      var createData = await createRes.json();
      if (createData.code === 200 && createData.data && createData.data.qrimg) {
        qrImage.innerHTML = '<img src="' + createData.data.qrimg + '" alt="扫码登录" />';
      } else {
        throw new Error('create failed');
      }
      qrImage.dataset.loaded = '1';

      startQRPolling(unikey);
    } catch (e) {
      qrImage.innerHTML = '<span style="font-size:12px;color:var(--color-accent);text-align:center;padding:1rem">本地代理未启动<br>请先运行 netease-proxy</span>';
    }
  }

  /* ─── 轮询扫码状态（加密接口）────────────────────────────── */
  function startQRPolling(unikey) {
    if (QR_POLL_INTERVAL) clearInterval(QR_POLL_INTERVAL);

    QR_POLL_INTERVAL = setInterval(async function () {
      try {
        var res = await fetch(PROXY_BASE + '/login/qr/check?key=' + encodeURIComponent(unikey) + '&timestamp=' + Date.now(), {
          credentials: 'include'
        });
        var data = await res.json();

        if (data.code === 800) {
          clearInterval(QR_POLL_INTERVAL);
          QR_POLL_INTERVAL = null;
          showLoginHint('二维码已过期，正在刷新...', 'error');
          generateQR();
        } else if (data.code === 801) {
          var t1 = qrImage.parentElement.querySelector('.login-qr__text');
          if (t1) t1.textContent = '请使用网易云音乐 App 扫码登录';
        } else if (data.code === 802) {
          showLoginHint('扫描成功，请在手机端点击确认登录', '');
        } else if (data.code === 803) {
          clearInterval(QR_POLL_INTERVAL);
          QR_POLL_INTERVAL = null;
          var cookie = data.cookie || '';
          if (cookie) {
            showLoginHint('登录成功，正在获取账号信息...', 'success');
            fetchUserInfoByCookie(cookie).then(function (info) {
              completeLogin(info.nickname, cookie, info.vipType);
            });
          } else {
            showLoginHint('登录成功！欢迎回来', 'success');
            completeLogin('火星人');
          }
        }
      } catch (e) {
        // 单次轮询失败，下次重试
      }
    }, 2000);
  }

  /* ─── 用 cookie 获取账号信息（昵称 / 会员类型）────────────── */
  async function fetchUserInfoByCookie(cookie) {
    try {
      var url = PROXY_BASE + '/user/account?timestamp=' + Date.now() + '&cookie=' + encodeURIComponent(cookie);
      var res = await fetch(url, { credentials: 'include' });
      var data = await res.json();
      if (data.code === 200 && data.profile) {
        return {
          nickname: data.profile.nickname || '火星人',
          vipType: data.profile.vipType || 0
        };
      }
    } catch (e) {}
    return { nickname: '火星人', vipType: 0 };
  }

  function showLoginHint(text, type) {
    loginHint.textContent = text;
    loginHint.classList.remove('is-error', 'is-success');
    if (type === 'error') loginHint.classList.add('is-error');
    if (type === 'success') loginHint.classList.add('is-success');
  }

  /* ════════════════════════════════════════════════════════════
     底部播放条 UI 控制器（订阅 player-core 事件）
     ════════════════════════════════════════════════════════════ */

  function formatTime(seconds) {
    if (!seconds || isNaN(seconds)) return '0:00';
    var min = Math.floor(seconds / 60);
    var sec = Math.floor(seconds % 60);
    return min + ':' + (sec < 10 ? '0' + sec : sec);
  }

  function updatePlayingTrack(idx) {
    tracks.forEach(function (t, i) {
      if (i === idx) t.classList.add('is-playing');
      else t.classList.remove('is-playing');
    });
  }

  function showPlayer() {
    player.classList.add('is-visible');
  }

  function hidePlayerBar() {
    player.classList.remove('is-visible');
    player.classList.remove('is-playing');
    player.classList.remove('is-loading');
    updatePlayingTrack(-1);
  }

  function renderPlayingState(playing) {
    viewPlaying = playing;
    if (playing) {
      player.classList.add('is-playing');
      playIcon.innerHTML = PAUSE_ICON;
    } else {
      player.classList.remove('is-playing');
      playIcon.innerHTML = PLAY_ICON;
    }
  }

  /* ─── 从 core 状态同步播放条（onShow / 登录完成后调用）───── */
  function syncPlayerFromCore() {
    if (!isLoggedIn) return;
    var st = window.MarsPlayer.getState();
    if (st.currentIndex >= 0 && st.song) {
      viewIndex = st.currentIndex;
      playerTitle.textContent = st.song.zh;
      playerSubtitle.textContent = st.song.en;
      updatePlayingTrack(st.currentIndex);
      renderPlayingState(st.isPlaying);
      player.classList.toggle('is-loading', st.isLoading);
      showPlayer();
      if (st.duration) {
        playerDuration.textContent = formatTime(st.duration);
        playerCurrentTime.textContent = formatTime(st.time);
        playerProgressFill.style.width = (st.time / st.duration * 100) + '%';
      }
    } else if (st.saved && st.playlist[st.saved.index]) {
      // 暂停态记忆：显示播放条选中曲目但保持暂停
      viewIndex = st.saved.index;
      var song = st.playlist[st.saved.index];
      playerTitle.textContent = song.zh;
      playerSubtitle.textContent = song.en;
      updatePlayingTrack(st.saved.index);
      renderPlayingState(false);
      showPlayer();
    }
  }

  /* ─── 订阅播放引擎事件 ────────────────────────────────────── */
  function bindCoreEvents() {
    window.addEventListener('marsplayer:trackchange', function (e) {
      var d = e.detail;
      viewIndex = d.index;
      if (d.index >= 0 && d.song) {
        playerTitle.textContent = d.song.zh;
        playerSubtitle.textContent = d.song.en;
        updatePlayingTrack(d.index);
        if (isLoggedIn) showPlayer();
      } else if (d.index === -1) {
        hidePlayerBar();
      }
    });

    window.addEventListener('marsplayer:playstate', function (e) {
      renderPlayingState(e.detail.playing);
    });

    window.addEventListener('marsplayer:loading', function (e) {
      player.classList.toggle('is-loading', e.detail.loading);
    });

    window.addEventListener('marsplayer:timeupdate', function (e) {
      var d = e.detail;
      playerProgressFill.style.width = d.pct + '%';
      playerCurrentTime.textContent = formatTime(d.time);
      playerDuration.textContent = formatTime(d.duration);
    });

    window.addEventListener('marsplayer:notice', function (e) {
      playerSubtitle.textContent = e.detail.msg;
    });

    window.addEventListener('marsplayer:error', function (e) {
      playerSubtitle.textContent = e.detail.msg;
      player.classList.remove('is-playing');
    });
  }

  /* ─── 视图内交互绑定（init 一次）─────────────────────────── */
  function bindViewEvents() {
    tracks.forEach(function (track, index) {
      track.addEventListener('click', function (e) {
        e.preventDefault();
        if (!isLoggedIn) {
          showLoginOverlay();
          return;
        }
        if (index === viewIndex) {
          window.MarsPlayer.toggle();
        } else {
          window.MarsPlayer.playTrack(index, 0);
        }
      });
      track.style.cursor = 'pointer';
    });

    playerPlay.addEventListener('click', function () { window.MarsPlayer.toggle(); });
    playerNext.addEventListener('click', function () { window.MarsPlayer.next(); });
    playerPrev.addEventListener('click', function () { window.MarsPlayer.prev(); });
    playerClose.addEventListener('click', function () {
      // 关闭播放条 = 明确停止（core 记录 playing:false，不再续播）
      window.MarsPlayer.stop();
    });

    playerProgressTrack.addEventListener('click', function (e) {
      var st = window.MarsPlayer.getState();
      if (!st.duration) return;
      var rect = playerProgressTrack.getBoundingClientRect();
      var pct = (e.clientX - rect.left) / rect.width;
      window.MarsPlayer.seek(pct * st.duration);
    });

    // 点击徽章可重新打开登录窗口（切换账号 / 重新登录）
    if (loginBadge) {
      loginBadge.style.cursor = 'pointer';
      loginBadge.addEventListener('click', function () {
        showLoginOverlay();
      });
    }

    // 登录相关
    loginSubmit.addEventListener('click', handlePhoneLogin);
    loginGuest.addEventListener('click', handleGuestMode);
    loginClose.addEventListener('click', handleGuestMode);
    codeBtn.addEventListener('click', handleSendCode);
    smsSubmit.addEventListener('click', handleSmsLogin);
    bindLoginTabs();
    bindLoginSubtabs();

    loginPassword.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') handlePhoneLogin();
    });
    smsCode.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') handleSmsLogin();
    });
    loginOverlay.addEventListener('click', function (e) {
      if (e.target === loginOverlay) handleGuestMode();
    });

    // 快捷键
    document.addEventListener('keydown', function (e) {
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
      // 仅在本视图激活时响应
      if (!window.MarsRouter || window.MarsRouter.current !== 'setlist') return;
      if (loginOverlay.classList.contains('is-visible')) {
        if (e.code === 'Escape') handleGuestMode();
        return;
      }
      if (!player.classList.contains('is-visible')) return;

      switch (e.code) {
        case 'Space':
          e.preventDefault();
          window.MarsPlayer.toggle();
          break;
        case 'ArrowRight':
          window.MarsPlayer.next();
          break;
        case 'ArrowLeft':
          window.MarsPlayer.prev();
          break;
        case 'Escape':
          window.MarsPlayer.stop();
          break;
      }
    });
  }

  /* ════════════════════════════════════════════════════════════
     视图生命周期
     ════════════════════════════════════════════════════════════ */
  window.MarsViews = window.MarsViews || {};
  window.MarsViews.setlist = {
    /* 首次注入后调用：DOM 引用 + 一次性绑定（DOM 缓存复用，幂等） */
    init: function (el) {
      if (initialized) return;
      initialized = true;
      rootEl = el;

      loginOverlay = $('loginOverlay');
      loginModal = $('loginModal');
      loginClose = $('loginClose');
      loginGuest = $('loginGuest');
      loginSubmit = $('loginSubmit');
      loginSpinner = $('loginSpinner');
      loginPhone = $('loginPhone');
      loginPassword = $('loginPassword');
      loginHint = $('loginHint');
      loginBadge = $('loginBadge');
      loginBadgeText = $('loginBadgeText');
      loginTabs = $('loginTabs');
      panelPhone = $('panelPhone');
      panelQr = $('panelQr');
      qrImage = $('qrImage');
      loginSubtabs = $('loginSubtabs');
      subpanelPassword = $('subpanelPassword');
      subpanelSms = $('subpanelSms');
      smsPhone = $('smsPhone');
      smsCode = $('smsCode');
      codeBtn = $('codeBtn');
      smsSubmit = $('smsSubmit');
      smsSpinner = $('smsSpinner');

      player = $('audioPlayer');
      playerTitle = $('playerTitle');
      playerSubtitle = $('playerSubtitle');
      playerPlay = $('playerPlay');
      playIcon = $('playIcon');
      playerPrev = $('playerPrev');
      playerNext = $('playerNext');
      playerClose = $('playerClose');
      playerProgressTrack = $('playerProgressTrack');
      playerProgressFill = $('playerProgressFill');
      playerCurrentTime = $('playerCurrentTime');
      playerDuration = $('playerDuration');

      tracks = Array.from(rootEl.querySelectorAll('.setlist-track'));
      colBodies = Array.from(rootEl.querySelectorAll('.setlist-col__body'));

      bindViewEvents();
      bindCoreEvents();
      initLogin();
    },

    /* 每次进入视图：同步 core 播放态（音频从不中断） */
    onShow: function () {
      if (!isLoggedIn) {
        // 未登录：弹窗 + 锁单（与现状一致）；音频若从首页带来则继续播放不干预
        showLoginOverlay();
        lockTracks();
      } else {
        syncPlayerFromCore();
      }
    },

    /* 离开视图：清理轮询/倒计时（DOM 保留，绑定存活） */
    destroy: function () {
      if (QR_POLL_INTERVAL) {
        clearInterval(QR_POLL_INTERVAL);
        QR_POLL_INTERVAL = null;
      }
      if (codeCountdown) {
        clearInterval(codeCountdown);
        codeCountdown = null;
        codeBtn.disabled = false;
        codeBtn.classList.remove('is-counting');
        codeBtn.textContent = '获取验证码';
      }
    }
  };
})();
