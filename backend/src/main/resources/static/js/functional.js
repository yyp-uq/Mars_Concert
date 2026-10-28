/* ════════════════════════════════════════════════════════════
   MARS CONCERT 2.0 · functional.js
   功能模块（SPA 视图化）：火星时间计时器/歌单交互/FAQ手风琴/分享
   生命周期：init(rootEl) 幂等绑定 + 启动计时器 / destroy() 清计时器
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  /* ─── Real show schedule (2026 Nanjing) ───────────────────── */
  const SHOWS = [
    { ts: '2026-09-25T19:00:00+08:00', label: 'Opening Night' },
    { ts: '2026-09-26T19:00:00+08:00', label: 'Show 2' },
    { ts: '2026-09-27T19:00:00+08:00', label: 'Mid-Autumn' },
    { ts: '2026-10-02T19:00:00+08:00', label: 'National Day' },
    { ts: '2026-10-03T19:00:00+08:00', label: 'Show 5' },
    { ts: '2026-10-05T19:00:00+08:00', label: 'Show 6' },
    { ts: '2026-10-06T19:00:00+08:00', label: 'Finale' },
  ].map(s => ({ ...s, date: new Date(s.ts) }));

  /* ─── 模块状态 ────────────────────────────────────────────── */
  let activeRoot = null;
  let rafId = null;
  let intervalId = null;
  let lastSec = -1;

  function $(sel) { return activeRoot ? activeRoot.querySelector(sel) : null; }
  function $$(sel) { return activeRoot ? Array.from(activeRoot.querySelectorAll(sel)) : []; }

  /* ─── Mars Time counter ───────────────────────────────────── */
  function pad(n) { return String(Math.floor(Math.abs(n))).padStart(2, '0'); }

  function updateMarsTime() {
    const dEl = $('#mtDays');
    if (!dEl) return;
    const hEl = $('#mtHours');
    const mEl = $('#mtMin');
    const sEl = $('#mtSec');
    const statusEl = $('#marsStatus');
    const captionEl = $('#marsCaption');
    const now = Date.now();

    // Find next upcoming show — 若后端已注入 window.__MARS_TARGET__，优先使用（动态场次）
    let targetDate;
    if (window.__MARS_TARGET__) {
      targetDate = new Date(window.__MARS_TARGET__);
    } else {
      const upcoming = SHOWS.find(s => s.date.getTime() > now);
      targetDate = upcoming ? upcoming.date : SHOWS[0].date;
    }

    if (targetDate.getTime() > now) {
      // Countdown mode
      const diff = targetDate.getTime() - now;
      const days = Math.floor(diff / 86400000);
      const hours = Math.floor((diff % 86400000) / 3600000);
      const mins = Math.floor((diff % 3600000) / 60000);
      const secs = Math.floor((diff % 60000) / 1000);

      dEl.textContent = pad(days);
      if (hEl) hEl.textContent = pad(hours);
      if (mEl) mEl.textContent = pad(mins);
      if (sEl) sEl.textContent = pad(secs);

      if (statusEl) statusEl.textContent = 'Countdown to MARS CONCERT';
      if (captionEl) captionEl.textContent = 'Mars homecoming · ' + targetDate.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) + ' · Nanjing Tangshan';
    } else {
      // All shows concluded → count up from opening night
      const opening = SHOWS[0].date.getTime();
      const elapsed = now - opening;
      const days = Math.floor(elapsed / 86400000);
      const hours = Math.floor((elapsed % 86400000) / 3600000);
      const mins = Math.floor((elapsed % 3600000) / 60000);
      const secs = Math.floor((elapsed % 60000) / 1000);

      dEl.textContent = pad(days);
      if (hEl) hEl.textContent = pad(hours);
      if (mEl) mEl.textContent = pad(mins);
      if (sEl) sEl.textContent = pad(secs);

      if (statusEl) statusEl.textContent = '2026 Season Concluded';
      if (captionEl) captionEl.textContent = 'Mars homecoming began ' + SHOWS[0].date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) + ' · 7 shows · 12 days of music';
    }
  }

  // Dual timer: setInterval (1s) + rAF fallback — either keeps ticking
  function marsTick() {
    const nowSec = Math.floor(Date.now() / 1000);
    if (nowSec !== lastSec) {
      lastSec = nowSec;
      updateMarsTime();
    }
    rafId = requestAnimationFrame(marsTick);
  }

  function startTimers() {
    stopTimers();
    updateMarsTime();
    marsTick();
    intervalId = setInterval(updateMarsTime, 1000);
  }

  function stopTimers() {
    if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
    if (intervalId) { clearInterval(intervalId); intervalId = null; }
  }

  /* ─── 事件绑定（每个 rootEl 仅绑定一次，片段 DOM 复用时存活）─── */
  function bindOnce(root) {
    if (root.__functionalBound) return;
    root.__functionalBound = true;

    /* ─── Setlist track interaction ─────────────────────────── */
    root.querySelectorAll('.setlist-track').forEach(track => {
      track.addEventListener('click', () => {
        // Deselect siblings in the same column
        const col = track.closest('.setlist-col');
        if (col) {
          col.querySelectorAll('.setlist-track.is-active').forEach(t => {
            if (t !== track) t.classList.remove('is-active');
          });
        }
        track.classList.toggle('is-active');
      });
    });

    /* ─── FAQ accordion ─────────────────────────────────────── */
    const faqItems = root.querySelectorAll('.faq-item');
    faqItems.forEach(item => {
      const question = item.querySelector('.faq-question');
      const answer = item.querySelector('.faq-answer');
      if (!question || !answer) return;

      question.addEventListener('click', () => {
        const isOpen = item.classList.contains('is-open');

        // Close all others
        faqItems.forEach(other => {
          if (other !== item && other.classList.contains('is-open')) {
            other.classList.remove('is-open');
            const a = other.querySelector('.faq-answer');
            if (a) a.style.maxHeight = '0px';
          }
        });

        if (isOpen) {
          item.classList.remove('is-open');
          answer.style.maxHeight = '0px';
        } else {
          item.classList.add('is-open');
          // Synchronous measurement: temporarily unrestrict max-height, measure, then set pixel value
          answer.style.maxHeight = 'none';
          const inner = answer.querySelector('.faq-answer__inner');
          const height = inner ? inner.offsetHeight : answer.scrollHeight;
          answer.style.maxHeight = (height || 200) + 'px';
        }
      });
    });

    /* ─── Share buttons ─────────────────────────────────────── */
    const shareText = 'MARS CONCERT 2.0 · MARS UTOPIA — Nanjing Tangshan Music Valley';
    // SPA：始终指向首页（去掉 hash 路由部分）
    const shareUrlObj = new URL('index.html', window.location.href);
    shareUrlObj.hash = '';
    const pageUrl = shareUrlObj.href;

    const shareLinks = {
      weibo: 'https://service.weibo.com/share/share.php?url=' + encodeURIComponent(pageUrl) + '&title=' + encodeURIComponent(shareText),
      twitter: 'https://twitter.com/intent/tweet?url=' + encodeURIComponent(pageUrl) + '&text=' + encodeURIComponent(shareText),
    };

    root.querySelectorAll('.share-btn[data-share]').forEach(btn => {
      const platform = btn.dataset.share;
      if (shareLinks[platform]) {
        btn.href = shareLinks[platform];
        btn.target = '_blank';
        btn.rel = 'noopener noreferrer';
      }
    });

    // Copy link — always show feedback regardless of clipboard API support
    const copyBtn = root.querySelector('.share-btn--copy');
    if (copyBtn) {
      copyBtn.addEventListener('click', async (e) => {
        e.preventDefault();
        // Attempt clipboard copy (may fail in non-secure contexts)
        try {
          await navigator.clipboard.writeText(pageUrl);
        } catch (err) {
          try {
            const input = document.createElement('input');
            input.value = pageUrl;
            input.style.position = 'fixed';
            input.style.opacity = '0';
            document.body.appendChild(input);
            input.select();
            document.execCommand('copy');
            document.body.removeChild(input);
          } catch (e2) { /* clipboard unavailable */ }
        }
        // Always show visual feedback
        copyBtn.classList.add('is-copied');
        const svg = copyBtn.querySelector('svg');
        let originalHTML = svg ? svg.outerHTML : '';
        if (svg) {
          svg.outerHTML = '<svg viewBox="0 0 24 24" width="18" height="18"><path d="M5 12l5 5L20 7" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        }
        setTimeout(() => {
          copyBtn.classList.remove('is-copied');
          const current = copyBtn.querySelector('svg');
          if (current && originalHTML) current.outerHTML = originalHTML;
        }, 2000);
      });
    }
  }

  /* ─── Recalculate FAQ max-height on resize ─────────────────── */
  let resizeTimer = null;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      if (!activeRoot) return;
      activeRoot.querySelectorAll('.faq-item.is-open .faq-answer').forEach(ans => {
        const inner = ans.querySelector('.faq-answer__inner');
        const h = inner ? inner.offsetHeight : ans.scrollHeight;
        ans.style.maxHeight = (h || 200) + 'px';
      });
    }, 200);
  });

  /* ─── SPA 视图接口 ─────────────────────────────────────────── */
  window.MarsViews = window.MarsViews || {};
  window.MarsViews.functional = {
    init(rootEl) {
      if (!rootEl) return;
      activeRoot = rootEl;
      bindOnce(rootEl);
      startTimers();
    },
    destroy() {
      stopTimers();
      activeRoot = null;
    }
  };
})();
