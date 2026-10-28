/* ════════════════════════════════════════════════════════════
   MARS CONCERT 2.0 · audio.js
   Web Audio API：BGM 播放控制 + 滚动联动音量
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ─── Audio setup ────────────────────────────────────────────
  let audioCtx = null;
  let gainNode = null;
  let sourceNode = null;
  let audioEl = null;
  let isPlaying = false;
  let currentVolume = 0;
  let targetVolume = 0;
  let fadeInterval = null;
  let analyserNode = null;
  let levelLoop = null;

  // Volume levels per act
  const actVolumes = {
    0: 0.15,  // HERO — low ambient
    1: 0.20,  // 序章 — low ambient
    2: 0.50,  // 舞台 — full
    3: 0.35,  // 钢架字 — medium
    4: 0.40,  // 水幕环 — medium-high
    5: 0.35,  // 水帘 — medium
    6: 0.70,  // 烟花终章 — maximum
  };

  // ─── Initialize audio context (requires user interaction) ──
  function initAudio() {
    if (audioCtx) return;

    try {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      gainNode = audioCtx.createGain();
      gainNode.gain.value = 0;
      gainNode.connect(audioCtx.destination);

      // Create audio element
      audioEl = new Audio();
      audioEl.src = 'assets/audio/bgm.mp3';
      audioEl.loop = true;
      audioEl.preload = 'auto';
      audioEl.crossOrigin = 'anonymous';

      sourceNode = audioCtx.createMediaElementSource(audioEl);
      analyserNode = audioCtx.createAnalyser();
      analyserNode.fftSize = 256;
      analyserNode.smoothingTimeConstant = 0.82;
      sourceNode.connect(analyserNode);
      analyserNode.connect(gainNode);

      audioEl.addEventListener('canplaythrough', () => {
        if (isPlaying) audioEl.play().catch(() => {});
      });

      audioEl.addEventListener('error', (e) => {
        // Audio file not found — silently disable
        console.warn('[audio] BGM file not available (bgm.mp3). Place audio at assets/audio/bgm.mp3');
        const toggle = document.getElementById('musicToggle');
        if (toggle) toggle.style.display = 'none';
      });
    } catch (err) {
      console.warn('[audio] Web Audio API not available:', err);
    }
  }

  // ─── Smooth volume fade ────────────────────────────────────
  function fadeVolume(to, duration) {
    if (!gainNode || !audioCtx) return;

    const from = currentVolume;
    const startTime = audioCtx.currentTime;
    const dur = duration || 1.5;

    gainNode.gain.cancelScheduledValues(startTime);
    gainNode.gain.setValueAtTime(from, startTime);
    gainNode.gain.linearRampToValueAtTime(to, startTime + dur);
    currentVolume = to;
  }

  // ─── Audio-reactive UI level (drives --audio-level CSS var) ─
  function startLevelLoop() {
    if (!analyserNode || levelLoop) return;
    const buf = new Uint8Array(analyserNode.frequencyBinCount);
    const tick = () => {
      analyserNode.getByteFrequencyData(buf);
      let sum = 0;
      for (let i = 0; i < buf.length; i++) sum += buf[i];
      const level = Math.min(1, sum / buf.length / 150);
      document.documentElement.style.setProperty('--audio-level', level.toFixed(3));
      levelLoop = requestAnimationFrame(tick);
    };
    levelLoop = requestAnimationFrame(tick);
  }

  function stopLevelLoop() {
    if (levelLoop) {
      cancelAnimationFrame(levelLoop);
      levelLoop = null;
    }
    document.documentElement.style.setProperty('--audio-level', '0');
  }

  // ─── Play / pause ───────────────────────────────────────────
  function play() {
    if (!audioCtx) initAudio();
    if (!audioCtx) return;

    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }

    isPlaying = true;
    if (audioEl) {
      audioEl.play().catch(() => {});
    }
    fadeVolume(targetVolume, 2);
    startLevelLoop();
    updateToggleButton(true);
  }

  function pause() {
    isPlaying = false;
    stopLevelLoop();
    fadeVolume(0, 0.8);
    setTimeout(() => {
      if (audioEl) audioEl.pause();
    }, 900);
    updateToggleButton(false);
  }

  function toggle() {
    if (isPlaying) pause();
    else play();
  }

  function updateToggleButton(playing) {
    const btn = document.getElementById('musicToggle');
    if (!btn) return;
    btn.classList.toggle('is-playing', playing);
    const onIcon = btn.querySelector('.music-toggle__icon--on');
    const offIcon = btn.querySelector('.music-toggle__icon--off');
    if (onIcon) onIcon.style.display = playing ? '' : 'none';
    if (offIcon) offIcon.style.display = playing ? 'none' : '';
  }

  // ─── Scroll-linked volume ───────────────────────────────────
  window.addEventListener('act:change', (e) => {
    const actNum = e.detail.actNum;
    targetVolume = actVolumes[actNum] ?? 0.3;
    if (isPlaying) {
      fadeVolume(targetVolume, 1.5);
    }
  });

  // ─── Music toggle button ───────────────────────────────────
  const musicBtn = document.getElementById('musicToggle');
  if (musicBtn) {
    musicBtn.addEventListener('click', () => {
      toggle();
    });
  }

  // ─── Auto-start on first user interaction ──────────────────
  // Browsers block autoplay until user interacts
  function autoStart() {
    // Only auto-start if user hasn't explicitly paused
    if (!isPlaying) {
      play();
    }
    document.removeEventListener('click', autoStart);
    document.removeEventListener('touchstart', autoStart);
    document.removeEventListener('keydown', autoStart);
  }

  // Don't auto-start — let user opt in
  // But listen for first interaction to enable audio context
  function enableOnFirstInteraction() {
    if (audioCtx) return;
    document.addEventListener('click', () => {
      if (!audioCtx) initAudio();
    }, { once: true });
  }

  // ─── Init ──────────────────────────────────────────────────
  function init() {
    enableOnFirstInteraction();
  }

  // ─── 对外 API（SPA 外壳层 BGM 引擎，供播放器互斥调用）───────
  window.MarsBGM = {
    play: play,
    pause: pause,
    toggle: toggle,
    isPlaying: function () { return isPlaying; }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
