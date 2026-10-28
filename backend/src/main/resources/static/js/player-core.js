/* ════════════════════════════════════════════════════════════
   MARS CONCERT · Player Core 统一播放引擎
   全站唯一持有 #hiddenAudio：播放/切歌/状态/缓存/互斥
   两个 UI 控制器（site-player / setlist-player）订阅事件驱动界面
   事件（window CustomEvent，marsplayer: 前缀）：
     trackchange {index, song}      曲目切换（index=-1 表示清空）
     playstate  {playing}           播放/暂停
     timeupdate {time,duration,pct} 进度
     loading    {loading}           取链加载态
     notice     {code,msg}          提示（trial/blocked/other-tab）
     error      {code,msg}          失败（vip/notfound/network）
   ════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var PROXY_BASE = 'http://localhost:3000';
  var STATE_KEY = 'mars_site_player_state';
  var LOGIN_KEY = 'mars_netease_login';
  var SONG_CACHE_KEY = 'mars_song_info_cache';
  var URL_CACHE_KEY = 'mars_audio_url_cache';
  var BCAST_KEY = 'mars_player_broadcast';

  /* ─── Twin Setlist 歌单（与 setlist 视图曲目顺序一致）─────── */
  var PLAYLIST = [
    { zh: '拆弹专家', en: 'Bomb Squad' },
    { zh: '我管你', en: "I Don't Care" },
    { zh: '飞行模式', en: 'Flight Mode' },
    { zh: '趁你还年轻', en: "Do It While You're Still Young" },
    { zh: '逃离乌托邦', en: 'Escape from Utopia' },
    { zh: '我们都是孤独的', en: 'We Are All Lonely' },
    { zh: '好想我回来啊', en: 'I Really Want Me Back' },
    { zh: '神树', en: 'God Tree' },
    { zh: '黑白艺术家', en: 'Black and White Artist' },
    { zh: '不朽', en: 'Immortal' },
    { zh: '永不熄灭的火焰', en: 'Eternal Flame' },
    { zh: '七重人格', en: 'Seven Personalities' },
    { zh: '好想爱这个世界啊', en: 'I Really Want to Love This World' },
    { zh: '虚幻与现实', en: 'Illusion and Reality' },
    { zh: '怪诞心理学', en: 'Bizarre Psychology' },
    { zh: '斗牛', en: 'Bullfight' },
    { zh: '忒修斯的船', en: "Theseus' Ship" },
    { zh: '降临', en: 'Advent' },
    { zh: '卡西莫多的礼物', en: "Quasimodo's Gift" },
    { zh: '异类', en: 'Alien' },
    { zh: '疯人院', en: 'Asylum' },
    { zh: '新世界', en: 'New World' },
    { zh: '点燃银河尽头的篝火', en: "Bonfire at the Galaxy's End" },
    { zh: 'For Forever', en: 'For Forever' },
    { zh: '国王与乞丐', en: 'King and Beggar' },
    { zh: '向阳而生', en: 'Born Toward the Sun' }
  ];

  /* ─── 状态 ────────────────────────────────────────────────── */
  var audio = null;
  var currentIndex = -1;
  var isPlaying = false;
  var isLoading = false;
  var currentIsVip = false;
  var currentIsTrial = false;
  var pendingSeek = 0;
  var savedState = null;        // 启动时载入的持久化状态（含暂停态记忆）
  var searchCache = {};
  var lastSaveTs = 0;
  var playToken = 0;            // 防止快速切歌时旧异步回调覆盖新状态

  var TAB_ID = Date.now() + '-' + Math.random().toString(36).slice(2, 8);

  /* ─── 事件派发 ────────────────────────────────────────────── */
  function emit(name, detail) {
    window.dispatchEvent(new CustomEvent('marsplayer:' + name, { detail: detail }));
  }

  /* ─── 会话 / cookie（网易云登录态，VIP 解锁）──────────────── */
  function getSession() {
    try {
      return JSON.parse(localStorage.getItem(LOGIN_KEY));
    } catch (e) {
      return null;
    }
  }

  function appendCookieParam(params) {
    var session = getSession();
    if (session && session.cookie) {
      params += '&cookie=' + encodeURIComponent(session.cookie);
    }
    return params;
  }

  /* ─── 音源/搜索持久化缓存 ─────────────────────────────────── */
  function readCache(key) { try { return JSON.parse(localStorage.getItem(key)) || {}; } catch (e) { return {}; } }

  function getCachedSongInfo(query) { var e = readCache(SONG_CACHE_KEY)[query]; return (e && e.id) ? e : null; }
  function cacheSongInfo(query, info) { try { var c = readCache(SONG_CACHE_KEY); c[query] = info; localStorage.setItem(SONG_CACHE_KEY, JSON.stringify(c)); } catch (e) {} }

  function getCachedAudioUrl(id) { var e = readCache(URL_CACHE_KEY)[id]; if (e && e.url && Date.now() - e.ts < 15 * 60 * 1000) return e.url; return null; }
  function cacheAudioUrl(id, url) { try { var c = readCache(URL_CACHE_KEY); c[id] = { url: url, ts: Date.now() }; localStorage.setItem(URL_CACHE_KEY, JSON.stringify(c)); } catch (e) {} }

  /* ─── 搜索歌曲（id + fee，fee=1 为 VIP 曲目）──────────────── */
  async function searchSong(zhName) {
    if (searchCache[zhName]) return searchCache[zhName];
    var query = '华晨宇 ' + zhName;
    var persisted = getCachedSongInfo(query);
    if (persisted) { searchCache[zhName] = persisted; return persisted; }
    try {
      var url = PROXY_BASE + '/cloudsearch?keywords=' + encodeURIComponent(query) + '&limit=1';
      var controller = new AbortController();
      var timeout = setTimeout(function () { controller.abort(); }, 8000);
      var res = await fetch(url, { signal: controller.signal });
      clearTimeout(timeout);
      var data = await res.json();
      if (data.code === 200 && data.result && data.result.songs && data.result.songs.length > 0) {
        var song = data.result.songs[0];
        var info = { id: song.id, fee: song.fee || 0 };
        searchCache[zhName] = info;
        cacheSongInfo(query, info);
        return info;
      }
    } catch (e) { /* 代理未启动 */ }
    return null;
  }

  /* ─── 跨页/刷新状态持久化 ─────────────────────────────────── */
  function saveState() {
    if (currentIndex === -1) return;
    try {
      localStorage.setItem(STATE_KEY, JSON.stringify({
        index: currentIndex,
        time: audio.currentTime || 0,
        playing: isPlaying,
        ts: Date.now()
      }));
    } catch (e) {}
  }

  function loadState() {
    try {
      return JSON.parse(localStorage.getItem(STATE_KEY));
    } catch (e) {
      return null;
    }
  }

  window.addEventListener('pagehide', saveState);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') saveState();
  });

  /* ─── 跨标签页互斥：任一标签开始播放 → 其他标签自动暂停 ───── */
  function broadcastPlay() {
    try {
      localStorage.setItem(BCAST_KEY, JSON.stringify({ tab: TAB_ID, ts: Date.now() }));
    } catch (e) {}
  }

  window.addEventListener('storage', function (e) {
    if (e.key !== BCAST_KEY || !e.newValue) return;
    try {
      var msg = JSON.parse(e.newValue);
      if (msg.tab !== TAB_ID && isPlaying) {
        audio.pause();
        setPlayingState(false);
        emit('notice', { code: 'other-tab', msg: '已在其他页面开始播放' });
      }
    } catch (err) {}
  });

  /* ─── BGM 互斥：歌曲开播前暂停环境音乐 ────────────────────── */
  function pauseBgmIfPlaying() {
    if (window.MarsBGM && window.MarsBGM.isPlaying && window.MarsBGM.isPlaying()) {
      window.MarsBGM.pause();
    }
  }

  /* ─── 内部状态工具 ────────────────────────────────────────── */
  function setPlayingState(playing) {
    if (isPlaying === playing) return;
    isPlaying = playing;
    emit('playstate', { playing: playing });
  }

  function setLoading(loading) {
    if (isLoading === loading) return;
    isLoading = loading;
    emit('loading', { loading: loading });
  }

  /* ════════════════════════════════════════════════════════════
     播放核心
     ════════════════════════════════════════════════════════════ */
  async function playTrack(index, seekTo) {
    if (index < 0) index = PLAYLIST.length - 1;
    if (index >= PLAYLIST.length) index = 0;

    var token = ++playToken;
    currentIndex = index;
    currentIsVip = false;
    currentIsTrial = false;
    pendingSeek = seekTo || 0;

    var song = PLAYLIST[index];
    emit('trackchange', { index: index, song: song });
    setLoading(true);

    var info = await searchSong(song.zh);
    if (token !== playToken) return; // 期间又切了别的歌
    setLoading(false);

    if (info && info.id) {
      if (info.fee === 1) currentIsVip = true;

      // 带 cookie 请求音源：黑胶/VIP 会员解锁 VIP 曲目完整音源
      // 优先命中持久化缓存（零网络请求）
      var audioUrl = getCachedAudioUrl(info.id);
      if (!audioUrl) {
        try {
          var params = appendCookieParam('id=' + info.id + '&br=320000&timestamp=' + Date.now());
          var res = await fetch(PROXY_BASE + '/song/url?' + params);
          var urlData = await res.json();
          if (urlData.code === 200 && urlData.data && urlData.data.length > 0) {
            if (urlData.data[0].fee === 1) currentIsVip = true;
            if (urlData.data[0].freeTrialInfo) currentIsTrial = true;
            audioUrl = urlData.data[0].url;
            if (audioUrl) cacheAudioUrl(info.id, audioUrl);
          }
        } catch (e) { /* 降级外链 */ }
      }
      if (token !== playToken) return;

      if (audioUrl) {
        audio.src = audioUrl;
        pauseBgmIfPlaying();
        if (currentIsTrial) {
          emit('notice', { code: 'trial', msg: 'VIP 曲目 · 试听片段（黑胶会员可听完整版）' });
        }
        startAudio(token);
      } else if (currentIsVip) {
        // VIP 曲目且当前账号无权限
        emit('error', { code: 'vip', msg: 'VIP 专属曲目 · 需网易云会员（黑胶）账号' });
        setPlayingState(false);
      } else {
        // 免费曲目：降级外链
        audio.src = 'https://music.163.com/song/media/outer/url?id=' + info.id + '.mp3';
        pauseBgmIfPlaying();
        startAudio(token);
      }
    } else {
      emit('error', { code: 'notfound', msg: '未找到音源 · 检查 netease-proxy 是否启动' });
      setPlayingState(false);
    }
  }

  function startAudio(token) {
    audio.play().then(function () {
      if (token !== playToken) return;
      broadcastPlay();
      setPlayingState(true);
      saveState();
    }).catch(function (err) {
      if (token !== playToken) return;
      if (err && err.name === 'NotAllowedError') {
        // 自动播放策略拦截：等待用户点击
        emit('notice', { code: 'blocked', msg: '点击播放继续收听' });
      } else {
        emit('error', {
          code: 'network',
          msg: currentIsVip ? 'VIP 专属曲目 · 需网易云会员（黑胶）账号' : '播放失败 · 请点击重试'
        });
      }
      setPlayingState(false);
    });
  }

  function toggle() {
    // 无活动曲目但有暂停态记忆 → 从记忆位置恢复
    if (currentIndex === -1) {
      if (savedState && typeof savedState.index === 'number' && savedState.index >= 0 && savedState.index < PLAYLIST.length) {
        var s = savedState;
        savedState = null;
        playTrack(s.index, s.time || 0);
      }
      return;
    }
    if (isPlaying) {
      audio.pause();
      setPlayingState(false);
      saveState();
    } else if (audio.src) {
      pauseBgmIfPlaying();
      audio.play().then(function () {
        broadcastPlay();
        setPlayingState(true);
        saveState();
      }).catch(function () {});
    }
  }

  function next() {
    playTrack((currentIndex + 1) % PLAYLIST.length, 0);
  }

  function prev() {
    playTrack(currentIndex <= 0 ? PLAYLIST.length - 1 : currentIndex - 1, 0);
  }

  function stop() {
    if (currentIndex === -1) return;
    audio.pause();
    setPlayingState(false);
    saveState(); // 记录 playing:false + 当前位置（刷新后可恢复选中态）
    currentIndex = -1;
    currentIsVip = false;
    currentIsTrial = false;
    pendingSeek = 0;
    emit('trackchange', { index: -1, song: null });
  }

  function getState() {
    return {
      currentIndex: currentIndex,
      isPlaying: isPlaying,
      isLoading: isLoading,
      isVip: currentIsVip,
      isTrial: currentIsTrial,
      song: currentIndex >= 0 ? PLAYLIST[currentIndex] : null,
      time: audio ? audio.currentTime || 0 : 0,
      duration: audio && audio.duration ? audio.duration : 0,
      saved: savedState,
      playlist: PLAYLIST
    };
  }

  /* ─── 启动：绑定唯一 audio 元素，恢复持久化状态 ───────────── */
  function boot() {
    audio = document.getElementById('hiddenAudio');
    if (!audio) return;

    audio.addEventListener('timeupdate', function () {
      if (audio.duration) {
        emit('timeupdate', {
          time: audio.currentTime,
          duration: audio.duration,
          pct: (audio.currentTime / audio.duration) * 100
        });
        // 播放中每 5s 持久化一次进度（刷新/崩溃可恢复）
        if (isPlaying && Date.now() - lastSaveTs > 5000) {
          lastSaveTs = Date.now();
          saveState();
        }
      }
    });

    audio.addEventListener('ended', function () {
      next();
    });

    audio.addEventListener('error', function () {
      if (currentIndex === -1) return;
      emit('error', {
        code: 'network',
        msg: currentIsVip ? 'VIP 专属曲目 · 需网易云会员（黑胶）账号' : '播放失败 · 请稍后重试'
      });
      setPlayingState(false);
    });

    // 恢复持久化状态：playing → 自动续播；paused → 记忆待首次交互恢复
    var saved = loadState();
    if (saved && typeof saved.index === 'number' && saved.index >= 0 && saved.index < PLAYLIST.length) {
      if (saved.playing) {
        playTrack(saved.index, saved.time || 0);
      } else {
        savedState = { index: saved.index, time: saved.time || 0 };
        // 通知 UI 存在暂停态记忆（曲名等可预先展示）
        emit('trackchange', { index: saved.index, song: PLAYLIST[saved.index], restored: true });
      }
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  /* ─── 对外 API ────────────────────────────────────────────── */
  window.MarsPlayer = {
    playTrack: playTrack,
    toggle: toggle,
    next: next,
    prev: prev,
    stop: stop,
    getState: getState,
    seek: function (t) { if (audio && audio.duration) audio.currentTime = t; }
  };
})();
