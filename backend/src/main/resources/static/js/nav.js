/* ════════════════════════════════════════════════════════════
   MARS CONCERT 2.0 · nav.js
   章节导航、参考图抽屉、圆形揭示返回首屏
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ─── Nav dot click → smooth scroll ─────────────────────────
  document.querySelectorAll('.nav-dot').forEach(dot => {
    dot.addEventListener('click', () => {
      const target = document.getElementById(dot.dataset.target);
      if (target) {
        target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    });
  });

  // ─── Reference drawer open/close ────────────────────────────
  const drawers = document.querySelectorAll('.ref-drawer');

  // Open buttons
  document.querySelectorAll('.ref-drawer-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const drawer = document.getElementById(btn.dataset.drawer);
      if (drawer) {
        drawer.classList.add('is-open');
        document.body.style.overflow = 'hidden';
      }
    });
  });

  // Close buttons
  document.querySelectorAll('.ref-drawer__close').forEach(btn => {
    btn.addEventListener('click', () => {
      const drawer = document.getElementById(btn.dataset.drawer);
      if (drawer) {
        drawer.classList.remove('is-open');
        document.body.style.overflow = '';
      }
    });
  });

  // Click outside to close
  drawers.forEach(drawer => {
    drawer.addEventListener('click', (e) => {
      if (e.target === drawer) {
        drawer.classList.remove('is-open');
        document.body.style.overflow = '';
      }
    });
  });

  // Escape to close
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      drawers.forEach(drawer => {
        if (drawer.classList.contains('is-open')) {
          drawer.classList.remove('is-open');
          document.body.style.overflow = '';
        }
      });
    }
  });

  // ─── Reference image lightbox (basic) ──────────────────────
  let lightbox = null;

  function createLightbox(src) {
    if (lightbox) lightbox.remove();

    lightbox = document.createElement('div');
    lightbox.style.cssText = `
      position:fixed;top:0;left:0;width:100vw;height:100vh;
      background:rgba(10,10,15,0.92);z-index:1500;
      display:flex;align-items:center;justify-content:center;
      cursor:zoom-out;backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
    `;

    const img = document.createElement('img');
    img.src = src;
    img.style.cssText = 'max-width:90vw;max-height:85vh;object-fit:contain;border-radius:2px;';
    lightbox.appendChild(img);

    lightbox.addEventListener('click', () => lightbox.remove());
    document.body.appendChild(lightbox);
    document.body.style.overflow = 'hidden';
  }

  document.querySelectorAll('.ref-drawer__img').forEach(img => {
    img.addEventListener('click', () => {
      createLightbox(img.src);
    });
  });

  // ─── Return to top with circular reveal ────────────────────
  const returnBtn = document.getElementById('returnToTop');
  const revealOverlay = document.getElementById('revealOverlay');

  if (returnBtn && revealOverlay) {
    returnBtn.addEventListener('click', (e) => {
      const rect = returnBtn.getBoundingClientRect();
      const x = rect.left + rect.width / 2;
      const y = rect.top + rect.height / 2;

      // Set reveal origin to button center
      revealOverlay.style.setProperty('--reveal-x', x + 'px');
      revealOverlay.style.setProperty('--reveal-y', y + 'px');
      revealOverlay.classList.add('is-active');

      // After reveal covers screen, jump to top
      setTimeout(() => {
        window.scrollTo({ top: 0, behavior: 'auto' });
        // Then reverse reveal to "open" the screen
        setTimeout(() => {
          revealOverlay.classList.remove('is-active');
          revealOverlay.style.clipPath = 'circle(150% at 50% 50%)';

          // Reset clip-path after transition
          setTimeout(() => {
            revealOverlay.style.clipPath = '';
          }, 800);
        }, 100);
      }, 800);
    });
  }

  // ─── Update nav dots on act:change ──────────────────────────
  window.addEventListener('act:change', (e) => {
    const actNum = e.detail.actNum;
    document.querySelectorAll('.nav-dot').forEach(dot => {
      dot.classList.toggle('active', dot.dataset.target === 'act-' + actNum);
    });
  });

  // ─── Scroll control: one act per gesture ───────────────────
  // 全 JS 驱动模式：拦截所有滚轮事件，GSAP 平滑动画到下一幕
  // 一次滚轮只翻一幕，零穿透。CSS snap 作为触摸/滚动条回退。
  // 运行时可通过 window.__snapConfig 动态调整（测试用）

  // 注册 ScrollToPlugin
  if (window.gsap && window.ScrollToPlugin) {
    window.gsap.registerPlugin(window.ScrollToPlugin);
  }

  let wheelLocked = false;
  const DRIVE_MS = 800;   // GSAP 动画时长
  const THRESHOLD = 15;    // 忽略微小滚动

  function getActTops() {
    return [...document.querySelectorAll('.act[data-act], .finale-card')].map(s => s.getBoundingClientRect().top + window.scrollY);
  }

  function scrollToAct(idx) {
    const acts = [...document.querySelectorAll('.act[data-act], .finale-card')];
    if (idx < 0 || idx >= acts.length) return;
    const tops = getActTops();
    const target = tops[idx];
    wheelLocked = true;

    // 动画期间临时禁用 CSS snap（内联 'none' 才能覆盖 CSS 类规则），防止干扰 GSAP
    const htmlEl = document.documentElement;
    const bodyEl = document.body;
    htmlEl.style.scrollSnapType = 'none';
    bodyEl.style.scrollSnapType = 'none';

    let unlocked = false;
    const unlock = () => {
      if (unlocked) return;
      unlocked = true;
      htmlEl.style.scrollSnapType = '';
      bodyEl.style.scrollSnapType = '';
      wheelLocked = false;
    };

    // 安全兜底：无论 GSAP 是否完成，到点必解锁，避免页面卡死
    const safetyTimer = setTimeout(unlock, DRIVE_MS + 200);

    if (window.gsap && window.ScrollToPlugin) {
      window.gsap.to(window, {
        duration: DRIVE_MS / 1000,
        scrollTo: { y: target, autoKill: false },
        ease: 'power2.out',
        onComplete: () => { clearTimeout(safetyTimer); unlock(); }
      });
    } else {
      window.scrollTo({ top: target, behavior: 'smooth' });
      setTimeout(() => { clearTimeout(safetyTimer); unlock(); }, DRIVE_MS);
    }
  }

  // 查找当前所在 act 索引
  function getCurrentActInfo() {
    const acts = [...document.querySelectorAll('.act[data-act], .finale-card')];
    const tops = getActTops();
    let idx = tops.findIndex(t => Math.abs(t - window.scrollY) < window.innerHeight * 0.4);
    if (idx < 0) idx = tops.reduce((best, t, i) => Math.abs(t - window.scrollY) < Math.abs(tops[best] - window.scrollY) ? i : best, 0);
    const act = acts[idx];
    const actTop = tops[idx] || 0;
    const actBottom = actTop + (act ? act.offsetHeight : window.innerHeight);
    const maxScroll = actBottom - window.innerHeight;
    return { acts, idx, act, actTop, actBottom, maxScroll };
  }

  window.addEventListener('wheel', (e) => {
    // SPA：非首页路由不拦截滚轮（子页自然滚动）
    if (window.MarsRouter && window.MarsRouter.current !== 'home') return;
    const cfg = window.__snapConfig || {};
    const mode = cfg.mode || 'drive';
    const threshold = cfg.threshold !== undefined ? cfg.threshold : THRESHOLD;

    // drive 模式：全拦截，JS 驱动滚动
    if (mode === 'drive') {
      const { acts, idx, act, actTop, maxScroll } = getCurrentActInfo();

      // 高幕（内容超出视口）→ 允许内部自然滚动，到边界才翻幕
      if (act && act.offsetHeight > window.innerHeight + 10) {
        if (e.deltaY > 0 && window.scrollY < maxScroll - 1) return; // 内部下滚
        if (e.deltaY < 0 && window.scrollY > actTop + 1) return;     // 内部上滚
      }

      e.preventDefault();
      e.stopPropagation();
      if (wheelLocked || Math.abs(e.deltaY) < threshold) return;
      const dir = e.deltaY > 0 ? 1 : -1;
      scrollToAct(Math.max(0, Math.min(acts.length - 1, idx + dir)));
      return;
    }

    // lock 模式（回退）：CSS snap + JS 节流
    if (mode === 'lock') {
      const lockMs = cfg.lockMs !== undefined ? cfg.lockMs : 900;
      if (wheelLocked) { e.preventDefault(); e.stopPropagation(); return; }
      if (Math.abs(e.deltaY) < threshold) return;
      wheelLocked = true;
      setTimeout(() => { wheelLocked = false; }, lockMs);
    }
    // mode === 'off' → 不干预，纯 CSS snap
  }, { passive: false, capture: true });

  // 键盘导航：方向键/空格/PgUp/PgDn → 一次翻一幕
  document.addEventListener('keydown', (e) => {
    // SPA：非首页路由不拦截键盘滚动
    if (window.MarsRouter && window.MarsRouter.current !== 'home') return;
    const cfg = window.__snapConfig || {};
    const mode = cfg.mode || 'drive';
    const navKeys = ['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', ' ', 'Home', 'End'];
    if (!navKeys.includes(e.key)) return;

    // 播放器胶囊展开时，Space 交给播放器（播放/暂停），避免双触发
    if (e.key === ' ' && document.querySelector('.site-music-widget.is-expanded')) return;

    if (mode === 'drive') {
      const { acts, idx, act, actTop, maxScroll } = getCurrentActInfo();

      // 高幕内部滚动：ArrowDown/PageDown 在底部之内 → 自然滚动
      if (act && act.offsetHeight > window.innerHeight + 10) {
        const goingDown = ['ArrowDown', 'PageDown', ' '].includes(e.key);
        const goingUp = ['ArrowUp', 'PageUp'].includes(e.key);
        if (goingDown && window.scrollY < maxScroll - 1) return; // 允许内部下滚
        if (goingUp && window.scrollY > actTop + 1) return;      // 允许内部上滚
      }

      e.preventDefault();
      if (wheelLocked) return;
      let next = idx;
      if (['ArrowDown', 'PageDown', ' '].includes(e.key)) next = Math.min(acts.length - 1, idx + 1);
      else if (['ArrowUp', 'PageUp'].includes(e.key)) next = Math.max(0, idx - 1);
      else if (e.key === 'Home') next = 0;
      else if (e.key === 'End') next = acts.length - 1;
      if (next !== idx) scrollToAct(next);
    } else if (mode === 'lock') {
      const lockMs = cfg.lockMs !== undefined ? cfg.lockMs : 900;
      if (wheelLocked) { e.preventDefault(); return; }
      wheelLocked = true;
      setTimeout(() => { wheelLocked = false; }, lockMs);
    }
  }, { passive: false, capture: true });
})();
