/* ════════════════════════════════════════════════════════════
   MARS CONCERT 2.0 · particles.js
   Canvas 粒子系统：火星尘埃 / 红色灯海 / 水波纹 / 无人机点阵
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  const canvas = document.getElementById('particleCanvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');

  const isMobile = window.matchMedia('(max-width: 767px)').matches;
  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  let W = 0, H = 0, dpr = 1;
  let particles = [];
  let ripples = [];
  let currentAct = 0;
  let rafId = null;
  let isActive = true;

  // ─── Resize ────────────────────────────────────────────────
  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = window.innerWidth;
    H = window.innerHeight;
    canvas.width = W * dpr;
    canvas.height = H * dpr;
    canvas.style.width = W + 'px';
    canvas.style.height = H + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  resize();
  window.addEventListener('resize', () => { resize(); rebuild(); });

  // ─── Particle factory ───────────────────────────────────────
  function makeParticle(type) {
    const p = {
      x: Math.random() * W,
      y: Math.random() * H,
      vx: 0, vy: 0,
      size: 1,
      alpha: 0,
      color: '#F5F5F7',
      life: 0,
      maxLife: 0,
      type: type,
    };

    switch (type) {
      case 'dust': // Mars dust — slow floating
        p.vx = (Math.random() - 0.5) * 0.15;
        p.vy = -Math.random() * 0.3 - 0.05;
        p.size = Math.random() * 1.5 + 0.5;
        p.alpha = Math.random() * 0.4 + 0.1;
        p.color = Math.random() > 0.7 ? '#E63946' : '#F4A261';
        break;

      case 'ember': // Red embers — stage lights
        p.vx = (Math.random() - 0.5) * 0.3;
        p.vy = Math.sin(Math.random() * Math.PI) * 0.2;
        p.size = Math.random() * 2 + 0.8;
        p.alpha = Math.random() * 0.5 + 0.2;
        p.color = '#E63946';
        p.swayPhase = Math.random() * Math.PI * 2;
        p.swaySpeed = Math.random() * 0.02 + 0.01;
        break;

      case 'water': // Water droplets — water curtain
        p.vx = (Math.random() - 0.5) * 0.1;
        p.vy = Math.random() * 1.5 + 0.5;
        p.size = Math.random() * 1.8 + 0.3;
        p.alpha = Math.random() * 0.3 + 0.15;
        p.color = '#5BB5E0';
        break;

      case 'drone': // Drone dots — fireworks finale
        p.targetX = Math.random() * W;
        p.targetY = Math.random() * H * 0.7 + H * 0.1;
        p.x = W / 2;
        p.y = H / 2;
        p.size = Math.random() * 1.5 + 0.5;
        p.alpha = 0;
        p.color = Math.random() > 0.5 ? '#F4A261' : '#E63946';
        p.maxLife = Math.random() * 300 + 200;
        p.life = 0;
        break;

      case 'spark': // Steel sparks — sign chapter
        p.vx = (Math.random() - 0.5) * 0.8;
        p.vy = -Math.random() * 0.5;
        p.size = Math.random() * 1.2 + 0.4;
        p.alpha = Math.random() * 0.6 + 0.3;
        p.color = '#F4A261';
        p.maxLife = Math.random() * 60 + 30;
        p.life = 0;
        break;
    }
    return p;
  }

  // ─── Rebuild particle array based on current act ────────────
  function rebuild() {
    particles = [];
    ripples = [];
    if (prefersReducedMotion) return;

    const count = getParticleCount();

    for (let i = 0; i < count; i++) {
      particles.push(makeParticle(getTypeForAct(currentAct)));
    }
  }

  function getParticleCount() {
    const mobile = window.matchMedia('(max-width: 767px)').matches;
    if (mobile) {
      switch (currentAct) {
        case 0: case 1: return 25;   // dust
        case 2: return 30;           // ember
        case 3: return 15;           // spark
        case 4: return 20;           // water
        case 5: return 25;           // water
        case 6: return 30;           // drone
        default: return 15;
      }
    }
    switch (currentAct) {
      case 0: case 1: return 80;  // dust
      case 2: return 120;         // ember
      case 3: return 60;          // spark
      case 4: return 70;          // water
      case 5: return 90;          // water
      case 6: return 100;         // drone
      default: return 50;
    }
  }

  function getTypeForAct(act) {
    switch (act) {
      case 0: case 1: return 'dust';
      case 2: return 'ember';
      case 3: return 'spark';
      case 4: case 5: return 'water';
      case 6: return 'drone';
      default: return 'dust';
    }
  }

  // ─── Update & render ────────────────────────────────────────
  function update() {
    for (let i = particles.length - 1; i >= 0; i--) {
      const p = particles[i];

      switch (p.type) {
        case 'dust':
          p.x += p.vx;
          p.y += p.vy;
          p.alpha = Math.max(0, p.alpha - 0.0005);
          if (p.y < -10 || p.alpha <= 0) {
            p.x = Math.random() * W;
            p.y = H + 10;
            p.alpha = Math.random() * 0.4 + 0.1;
          }
          break;

        case 'ember':
          p.swayPhase += p.swaySpeed;
          p.x += p.vx + Math.sin(p.swayPhase) * 0.3;
          p.y += p.vy;
          if (p.y < -10) { p.y = H + 10; p.x = Math.random() * W; }
          if (p.x < -10) p.x = W + 10;
          if (p.x > W + 10) p.x = -10;
          break;

        case 'water':
          p.x += p.vx;
          p.y += p.vy;
          if (p.y > H + 10) {
            // Create ripple
            if (Math.random() > 0.7) {
              ripples.push({ x: p.x, y: H - 5, r: 0, alpha: 0.3 });
            }
            p.y = -10;
            p.x = Math.random() * W;
          }
          break;

        case 'drone':
          p.life++;
          p.x += (p.targetX - p.x) * 0.02;
          p.y += (p.targetY - p.y) * 0.02;
          p.alpha = Math.min(0.8, p.life / 60);
          if (p.life > p.maxLife) {
            p.alpha = Math.max(0, p.alpha - 0.02);
            if (p.alpha <= 0) {
              p.targetX = Math.random() * W;
              p.targetY = Math.random() * H * 0.7 + H * 0.1;
              p.life = 0;
              p.x = W / 2;
              p.y = H / 2;
            }
          }
          break;

        case 'spark':
          p.life++;
          p.x += p.vx;
          p.y += p.vy;
          p.vy += 0.02; // gravity
          p.alpha = Math.max(0, 1 - p.life / p.maxLife);
          if (p.life >= p.maxLife) {
            p.x = Math.random() * W;
            p.y = Math.random() * H * 0.5;
            p.vx = (Math.random() - 0.5) * 0.8;
            p.vy = -Math.random() * 0.5;
            p.life = 0;
          }
          break;
      }
    }

    // Update ripples
    for (let i = ripples.length - 1; i >= 0; i--) {
      ripples[i].r += 1.5;
      ripples[i].alpha -= 0.01;
      if (ripples[i].alpha <= 0) ripples.splice(i, 1);
    }
  }

  function render() {
    ctx.clearRect(0, 0, W, H);

    // Draw particles with glow
    for (const p of particles) {
      ctx.beginPath();
      ctx.fillStyle = p.color;
      ctx.globalAlpha = p.alpha;

      // Add glow for larger particles
      if (p.size > 1.2) {
        ctx.shadowBlur = p.size * 4;
        ctx.shadowColor = p.color;
      } else {
        ctx.shadowBlur = 0;
      }

      ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
      ctx.fill();
    }

    ctx.shadowBlur = 0;

    // Draw ripples
    ctx.globalAlpha = 1;
    for (const r of ripples) {
      ctx.beginPath();
      ctx.strokeStyle = '#5BB5E0';
      ctx.globalAlpha = r.alpha;
      ctx.lineWidth = 0.8;
      ctx.arc(r.x, r.y, r.r, 0, Math.PI * 2);
      ctx.stroke();
    }

    ctx.globalAlpha = 1;
  }

  function loop() {
    if (!isActive) return;
    update();
    render();
    rafId = requestAnimationFrame(loop);
  }

  // ─── Start / stop based on visibility ──────────────────────
  function start() {
    if (rafId || prefersReducedMotion) return;
    isActive = true;
    loop();
  }

  function stop() {
    isActive = false;
    if (rafId) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    ctx.clearRect(0, 0, W, H);
  }

  // ─── Act change handler ────────────────────────────────────
  window.addEventListener('act:change', (e) => {
    currentAct = e.detail.actNum;
    rebuild();
    start();
  });

  // ─── SPA 路由门控：子视图隐藏 canvas 时暂停粒子循环 ─────────
  let routePaused = false;
  window.MarsParticles = {
    pause: function () { routePaused = true; stop(); },
    resume: function () { routePaused = false; start(); }
  };

  // ─── Pause when tab hidden ─────────────────────────────────
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stop();
    else if (!routePaused) start();
  });

  // ─── Init ──────────────────────────────────────────────────
  function init() {
    resize();
    rebuild();
    start();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
