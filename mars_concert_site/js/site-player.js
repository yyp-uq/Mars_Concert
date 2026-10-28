/* ════════════════════════════════════════════════════════════
   MARS CONCERT · Site Player 悬浮唱片播放器（纯 UI 控制器）
   首页 / Mission / Artist 视图右上角黑胶圆盘（Setlist 视图由 CSS 隐藏）
   · 收起态：旋转黑胶圆盘 + 金色进度环 + hover 曲名标签
   · 展开态：玻璃拟态胶囊（曲名 / 进度 / 控制 / BGM 开关）
   · 播放引擎由 player-core.js 统一持有，本模块只订阅事件渲染
   ════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var widget = null;
  var el = {};

  /* UI 镜像状态（由 marsplayer 事件驱动） */
  var viewIndex = -1;
  var viewPlaying = false;
  var playlist = [];

  var PLAY_ICON = '<path d="M8 5v14l11-7-11-7z" fill="currentColor"/>';
  var PAUSE_ICON = '<path d="M6 4h4v16H6V4zm8 0h4v16h-4V4z" fill="currentColor"/>';

  /* ════════════════════════════════════════════════════════════
     样式：悬浮唱片 · 火星轨道
     ════════════════════════════════════════════════════════════ */
  var CSS = [
    '.site-music-widget{}',

    /* ── 黑胶圆盘（收起态入口，独立定位于右上角）── */
    '.site-music-disc{position:fixed;top:76px;right:20px;z-index:1100;width:48px;height:48px;border-radius:50%;',
    'background:radial-gradient(circle at 50% 50%,#1a1216 0%,#0a0a0f 70%);',
    'border:1px solid rgba(230,57,70,.5);cursor:pointer;padding:0;',
    'box-shadow:0 4px 20px rgba(0,0,0,.5),inset 0 0 10px rgba(230,57,70,.12);',
    'transition:transform .35s cubic-bezier(.16,1,.3,1),box-shadow .35s,border-color .35s}',
    '.site-music-disc:hover{transform:scale(1.08);border-color:#e63946;',
    'box-shadow:0 6px 28px rgba(230,57,70,.28),inset 0 0 12px rgba(230,57,70,.2)}',
    /* 唱纹层（圆对称渐变，无需旋转） */
    '.site-music-disc__vinyl{position:absolute;inset:5px;border-radius:50%;',
    'background:repeating-radial-gradient(circle at 50% 50%,rgba(212,162,78,.28) 0 1px,transparent 1px 4px),',
    'radial-gradient(circle at 50% 50%,#e63946 0 22%,rgba(230,57,70,.12) 23% 100%)}',
    /* 高光弧（静态质感层，不做动画——conic 动画会导致弱内核渲染卡死） */
    '.site-music-disc__sheen{position:absolute;inset:5px;border-radius:50%;',
    'background:conic-gradient(from 20deg,transparent 0 288deg,rgba(255,235,205,.16) 318deg,transparent 340deg)}',
    '.site-music-disc.is-playing{border-color:rgba(230,57,70,.75)}',
    /* 播放态：中心火星点脉冲（transform+opacity，合成器友好零重绘） */
    '.site-music-disc.is-playing::after{animation:sitePulse 1.8s ease-in-out infinite}',
    /* 中心火星点（播放时被唱纹覆盖视觉，暂停时作为提示） */
    '.site-music-disc::after{content:"";position:absolute;inset:0;margin:auto;width:7px;height:7px;',
    'border-radius:50%;background:radial-gradient(circle,#f5d9a8 0%,#d4a24e 70%);',
    'box-shadow:0 0 6px rgba(212,162,78,.8);pointer-events:none;z-index:2}',
    /* 金色进度环（conic + 环形遮罩） */
    '.site-music-disc__progress{position:absolute;inset:-4px;border-radius:50%;pointer-events:none;',
    'background:conic-gradient(#d4a24e calc(var(--pct,0)*1%),transparent 0);',
    '-webkit-mask:radial-gradient(farthest-side,transparent calc(100% - 3px),#000 calc(100% - 2px));',
    'mask:radial-gradient(farthest-side,transparent calc(100% - 3px),#000 calc(100% - 2px));',
    'transition:opacity .3s}',
    /* ── hover 曲名标签（仅在收起态显示）── */
    '.site-music-tip{position:fixed;top:100px;right:78px;z-index:1100;pointer-events:none;',
    'white-space:nowrap;padding:7px 14px;border-radius:20px;background:rgba(12,12,18,.92);',
    'border:1px solid rgba(212,162,78,.3);color:#f5f0e8;font-size:12px;letter-spacing:.06em;',
    'font-family:var(--font-serif-zh,serif);opacity:0;transform:translateX(6px);',
    'transition:opacity .3s,transform .3s cubic-bezier(.16,1,.3,1)}',
    '.site-music-widget:not(.is-expanded):hover .site-music-tip{opacity:1;transform:translateX(0)}',
    '.site-music-tip em{font-style:normal;color:rgba(212,162,78,.9);font-size:10px;margin-left:8px;letter-spacing:.1em}',

    /* ── 玻璃胶囊（展开态，独立定位于圆盘左侧）── */
    '.site-music-capsule{position:fixed;top:76px;right:78px;z-index:1099;display:flex;align-items:center;gap:.75rem;height:56px;',
    'padding:0 16px 0 12px;border-radius:28px;background:rgba(12,12,18,.94);',
    'border:1px solid rgba(230,57,70,.35);box-shadow:0 10px 40px rgba(0,0,0,.55);',
    'opacity:0;transform:translateX(14px) scale(.92);transform-origin:right center;',
    'pointer-events:none;transition:opacity .35s cubic-bezier(.16,1,.3,1),transform .35s cubic-bezier(.16,1,.3,1)}',
    '.site-music-widget.is-expanded .site-music-capsule{opacity:1;transform:translateX(0) scale(1);pointer-events:auto}',
    '.site-music-capsule__collapse{display:flex;align-items:center;justify-content:center;width:30px;height:30px;',
    'border-radius:50%;background:none;border:none;color:rgba(245,240,232,.45);cursor:pointer;flex-shrink:0;transition:all .2s}',
    '.site-music-capsule__collapse:hover{color:#f5f0e8;background:rgba(255,255,255,.07)}',
    '.site-music-capsule__collapse svg{width:15px;height:15px}',
    '.site-music-capsule__info{display:flex;flex-direction:column;gap:1px;min-width:0;max-width:150px}',
    '.site-music-capsule__title{font-family:var(--font-serif-zh,serif);font-size:14px;font-weight:600;',
    'color:#f5f0e8;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;letter-spacing:.05em}',
    '.site-music-capsule__subtitle{font-size:10px;color:rgba(245,240,232,.45);letter-spacing:.08em;',
    'white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.site-music-capsule__progress{display:flex;align-items:center;gap:.4rem;flex-shrink:0}',
    '.site-music-capsule__progress span{font-variant-numeric:tabular-nums;font-size:10px;color:rgba(245,240,232,.4)}',
    '.site-music-capsule__track{width:70px;height:3px;background:rgba(255,255,255,.1);border-radius:2px;cursor:pointer;position:relative;overflow:hidden}',
    '.site-music-capsule__fill{position:absolute;top:0;left:0;height:100%;width:0%;',
    'background:linear-gradient(90deg,#e63946,#d4a24e);border-radius:2px}',
    '.site-music-capsule__controls{display:flex;align-items:center;gap:.15rem;flex-shrink:0}',
    '.site-music-capsule__btn{display:flex;align-items:center;justify-content:center;width:32px;height:32px;',
    'border-radius:50%;background:none;border:none;color:rgba(245,240,232,.6);cursor:pointer;transition:all .2s}',
    '.site-music-capsule__btn:hover{color:#f5f0e8;background:rgba(255,255,255,.07)}',
    '.site-music-capsule__btn svg{width:16px;height:16px}',
    '.site-music-capsule__btn--play{width:38px;height:38px;color:#e63946;background:rgba(230,57,70,.1);',
    'border:1px solid rgba(230,57,70,.35)}',
    '.site-music-capsule__btn--play:hover{background:rgba(230,57,70,.2);color:#e63946;border-color:#e63946}',
    '.site-music-capsule__btn--play svg{width:18px;height:18px}',
    /* ── 胶囊内 BGM 开关 ── */
    '.site-music-capsule__btn--bgm{color:rgba(212,162,78,.55)}',
    '.site-music-capsule__btn--bgm:hover{color:#d4a24e;background:rgba(212,162,78,.1)}',
    '.site-music-capsule__btn--bgm.is-on{color:#d4a24e}',
    '.site-music-capsule__btn--bgm.is-on svg{filter:drop-shadow(0 0 4px rgba(212,162,78,.6))}',

    /* ── 加载态 ── */
    '.site-music-disc.is-loading .site-music-disc__vinyl{opacity:.25}',
    '.site-music-disc.is-loading::before{content:"";position:absolute;inset:14px;border-radius:50%;',
    'border:2px solid rgba(230,57,70,.25);border-top-color:#e63946;animation:siteSpin .8s linear infinite}',

    '@keyframes siteSpin{to{transform:rotate(360deg)}}',
    '@keyframes sitePulse{0%,100%{transform:scale(1);opacity:1}50%{transform:scale(1.7);opacity:.7}}',

    /* ── 移动端 ── */
    '@media (max-width:768px){',
    '.site-music-disc{top:70px;right:14px;width:44px;height:44px}',
    '.site-music-capsule{top:70px;right:66px;height:52px;gap:.5rem;padding:0 12px 0 10px;max-width:calc(100vw - 80px)}',
    '.site-music-tip{top:92px;right:66px}',
    '.site-music-capsule__info{max-width:110px}',
    '.site-music-capsule__track{width:48px}}'
  ].join('\n');

  function injectStyle() {
    var style = document.createElement('style');
    style.id = 'sitePlayerStyle';
    style.textContent = CSS;
    document.head.appendChild(style);
  }

  /* ─── DOM 注入 ────────────────────────────────────────────── */
  var CHEVRON_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>';
  var PREV_SVG = '<svg viewBox="0 0 24 24"><path d="M19 20L9 12l10-8v16zM7 19V5" fill="currentColor" stroke="none"/></svg>';
  var NEXT_SVG = '<svg viewBox="0 0 24 24"><path d="M5 4l10 8-10 8V4zM17 5v14" fill="currentColor" stroke="none"/></svg>';
  var BGM_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4V5z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9 9 0 0 1 0 13"/></svg>';

  function injectDOM() {
    widget = document.createElement('div');
    widget.className = 'site-music-widget';
    widget.id = 'siteMusicWidget';

    var capsule = document.createElement('div');
    capsule.className = 'site-music-capsule';
    capsule.innerHTML =
      '<button class="site-music-capsule__collapse" id="sitePlayerClose" title="收起">' + CHEVRON_SVG + '</button>' +
      '<div class="site-music-capsule__info">' +
        '<span class="site-music-capsule__title" id="sitePlayerTitle">MARS CONCERT</span>' +
        '<span class="site-music-capsule__subtitle" id="sitePlayerSubtitle">Twin Setlist</span>' +
      '</div>' +
      '<div class="site-music-capsule__progress">' +
        '<span id="siteCurrentTime">0:00</span>' +
        '<div class="site-music-capsule__track" id="siteProgressTrack">' +
          '<div class="site-music-capsule__fill" id="siteProgressFill"></div>' +
        '</div>' +
        '<span id="siteDuration">0:00</span>' +
      '</div>' +
      '<div class="site-music-capsule__controls">' +
        '<button class="site-music-capsule__btn" id="sitePlayerPrev" title="上一首">' + PREV_SVG + '</button>' +
        '<button class="site-music-capsule__btn site-music-capsule__btn--play" id="sitePlayerPlay" title="播放/暂停">' +
          '<svg viewBox="0 0 24 24" id="sitePlayIcon">' + PLAY_ICON + '</svg>' +
        '</button>' +
        '<button class="site-music-capsule__btn" id="sitePlayerNext" title="下一首">' + NEXT_SVG + '</button>' +
        '<button class="site-music-capsule__btn site-music-capsule__btn--bgm" id="sitePlayerBgm" title="环境音乐 Ambient BGM">' + BGM_SVG + '</button>' +
      '</div>';

    var disc = document.createElement('button');
    disc.className = 'site-music-disc';
    disc.id = 'siteMusicDisc';
    disc.setAttribute('aria-label', '歌曲播放器 Now Playing');
    disc.innerHTML =
      '<span class="site-music-disc__vinyl"></span>' +
      '<span class="site-music-disc__sheen"></span>' +
      '<span class="site-music-disc__progress"></span>';

    var tip = document.createElement('div');
    tip.className = 'site-music-tip';
    tip.id = 'siteMusicTip';
    tip.innerHTML = 'MARS CONCERT<em>TWIN SETLIST</em>';

    widget.appendChild(capsule);
    widget.appendChild(disc);
    widget.appendChild(tip);
    document.body.appendChild(widget);

    el.disc = document.getElementById('siteMusicDisc');
    el.tip = document.getElementById('siteMusicTip');
    el.title = document.getElementById('sitePlayerTitle');
    el.subtitle = document.getElementById('sitePlayerSubtitle');
    el.playBtn = document.getElementById('sitePlayerPlay');
    el.playIcon = document.getElementById('sitePlayIcon');
    el.prev = document.getElementById('sitePlayerPrev');
    el.next = document.getElementById('sitePlayerNext');
    el.close = document.getElementById('sitePlayerClose');
    el.track = document.getElementById('siteProgressTrack');
    el.fill = document.getElementById('siteProgressFill');
    el.cur = document.getElementById('siteCurrentTime');
    el.dur = document.getElementById('siteDuration');
    el.bgm = document.getElementById('sitePlayerBgm');

    // 首页旧 BGM 圆钮隐藏（控制收进胶囊，直接调 MarsBGM API）
    var legacyToggle = document.getElementById('musicToggle');
    if (legacyToggle) legacyToggle.style.display = 'none';
    syncBgmState();
  }

  /* ─── BGM 状态同步与切换 ──────────────────────────────────── */
  function syncBgmState() {
    if (el.bgm && window.MarsBGM) {
      el.bgm.classList.toggle('is-on', window.MarsBGM.isPlaying());
    }
  }

  function toggleBgm() {
    if (!window.MarsBGM) return;
    window.MarsBGM.toggle();
    syncBgmState();
  }

  /* ─── 工具 ────────────────────────────────────────────────── */
  function formatTime(seconds) {
    if (!seconds || isNaN(seconds)) return '0:00';
    var min = Math.floor(seconds / 60);
    var sec = Math.floor(seconds % 60);
    return min + ':' + (sec < 10 ? '0' + sec : sec);
  }

  function renderPlayingState(playing) {
    viewPlaying = playing;
    if (playing) {
      el.disc.classList.add('is-playing');
      el.disc.classList.remove('is-loading');
      el.playIcon.innerHTML = PAUSE_ICON;
    } else {
      el.disc.classList.remove('is-playing');
      el.playIcon.innerHTML = PLAY_ICON;
    }
    updateTip();
    // 歌曲开播会暂停 BGM（core 侧互斥），重新同步 BGM 按钮态
    if (playing) syncBgmState();
  }

  function renderLoading(loading) {
    el.disc.classList.toggle('is-loading', loading);
  }

  function expandPlayer() {
    widget.classList.add('is-expanded');
  }

  function collapsePlayer() {
    widget.classList.remove('is-expanded');
  }

  function isExpanded() {
    return widget.classList.contains('is-expanded');
  }

  function updateTip() {
    if (viewIndex < 0 || !playlist[viewIndex]) return;
    var song = playlist[viewIndex];
    var state = viewPlaying ? '' : '<em>PAUSED</em>';
    el.tip.innerHTML = song.zh + '<em>' + song.en + '</em>' + state;
  }

  /* ─── 圆盘点击：展开胶囊；无活动曲目时开始播放 ───────────── */
  function onDiscClick() {
    if (isExpanded()) {
      collapsePlayer();
      return;
    }
    expandPlayer();
    var st = window.MarsPlayer.getState();
    if (st.currentIndex === -1) {
      if (st.saved) {
        // 有暂停态记忆 → 从记忆位置续播
        window.MarsPlayer.toggle();
      } else {
        // 全新会话 → 从第一首开始
        window.MarsPlayer.playTrack(0, 0);
      }
    }
  }

  /* ─── 事件绑定 ────────────────────────────────────────────── */
  function bindEvents() {
    el.disc.addEventListener('click', onDiscClick);

    el.playBtn.addEventListener('click', function () { window.MarsPlayer.toggle(); });
    el.next.addEventListener('click', function () { window.MarsPlayer.next(); });
    el.prev.addEventListener('click', function () { window.MarsPlayer.prev(); });
    el.close.addEventListener('click', collapsePlayer);
    el.bgm.addEventListener('click', toggleBgm);

    el.track.addEventListener('click', function (e) {
      var st = window.MarsPlayer.getState();
      if (!st.duration) return;
      var rect = el.track.getBoundingClientRect();
      window.MarsPlayer.seek(((e.clientX - rect.left) / rect.width) * st.duration);
    });

    // 快捷键：Space 播放/暂停 · Esc 收起胶囊
    document.addEventListener('keydown', function (e) {
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
      switch (e.code) {
        case 'Space':
          if (!isExpanded()) break;
          e.preventDefault();
          window.MarsPlayer.toggle();
          break;
        case 'Escape':
          collapsePlayer();
          break;
      }
    });
  }

  /* ─── 订阅播放引擎事件 ────────────────────────────────────── */
  function bindCoreEvents() {
    playlist = window.MarsPlayer.getState().playlist;

    window.addEventListener('marsplayer:trackchange', function (e) {
      var d = e.detail;
      viewIndex = d.index;
      if (d.index >= 0 && d.song) {
        el.title.textContent = d.song.zh;
        el.subtitle.textContent = d.song.en;
      }
      updateTip();
    });

    window.addEventListener('marsplayer:playstate', function (e) {
      renderPlayingState(e.detail.playing);
    });

    window.addEventListener('marsplayer:loading', function (e) {
      renderLoading(e.detail.loading);
    });

    window.addEventListener('marsplayer:timeupdate', function (e) {
      var d = e.detail;
      el.fill.style.width = d.pct + '%';
      el.disc.style.setProperty('--pct', d.pct.toFixed(1));
      el.cur.textContent = formatTime(d.time);
      el.dur.textContent = formatTime(d.duration);
    });

    window.addEventListener('marsplayer:notice', function (e) {
      el.subtitle.textContent = e.detail.msg;
      if (e.detail.code === 'other-tab') updateTip();
    });

    window.addEventListener('marsplayer:error', function (e) {
      el.subtitle.textContent = e.detail.msg;
    });

    // 启动时同步一次（core 可能已恢复播放/记忆）
    var st = window.MarsPlayer.getState();
    if (st.currentIndex >= 0 && st.song) {
      viewIndex = st.currentIndex;
      el.title.textContent = st.song.zh;
      el.subtitle.textContent = st.song.en;
    } else if (st.saved && playlist[st.saved.index]) {
      viewIndex = st.saved.index;
      el.title.textContent = playlist[st.saved.index].zh;
      el.subtitle.textContent = playlist[st.saved.index].en;
    }
    renderPlayingState(st.isPlaying);
    renderLoading(st.isLoading);
    updateTip();
  }

  /* ─── 启动 ───────────────────────────────────────────────── */
  injectStyle();
  injectDOM();
  bindEvents();
  bindCoreEvents();
})();
