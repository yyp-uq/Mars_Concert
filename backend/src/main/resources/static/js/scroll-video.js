/* ════════════════════════════════════════════════════════════
   MARS CONCERT 2.0 · scroll-video.js
   滚动驱动视频进度 + 自动播放管理
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const isMobile = window.matchMedia('(max-width: 767px)').matches;

  // Acts that use scroll-driven scrubbing (vs loop playback)
  const scrubActs = new Set([0]); // HERO uses scrub
  // Acts that loop video continuously
  const loopActs = new Set([1, 2, 3, 4, 5, 6]);

  let gsapReady = false;
  let triggers = [];
  let loopObservers = [];

  function initGSAP() {
    if (typeof gsap === 'undefined' || typeof ScrollTrigger === 'undefined') return false;
    gsap.registerPlugin(ScrollTrigger);
    gsapReady = true;
    return true;
  }

  // ─── Setup scroll-driven video for HERO ─────────────────────
  function setupScrubVideo(actEl, video, actNum) {
    if (!gsapReady || prefersReducedMotion) {
      // Fallback: just autoplay muted loop
      setupLoopVideo(video);
      return;
    }

    // Wait for video to be ready
    const onLoaded = () => {
      const st = ScrollTrigger.create({
        trigger: actEl,
        start: 'top top',
        end: 'bottom top',
        scrub: 0.5,
        onUpdate: (self) => {
          if (video.readyState >= 2 && video.duration) {
            video.currentTime = self.progress * video.duration;
          }
        },
      });
      triggers.push(st);
    };

    if (video.readyState >= 2) {
      onLoaded();
    } else {
      video.addEventListener('loadeddata', onLoaded, { once: true });
    }
  }

  // ─── Setup loop video for other acts ────────────────────────
  function setupLoopVideo(video) {
    video.loop = true;
    video.muted = true;
    video.playsInline = true;

    // Play when video enters viewport
    const observer = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          video.play().catch(() => {});
        } else {
          video.pause();
        }
      });
    }, { threshold: 0.3 });

    observer.observe(video);
    loopObservers.push(observer);
  }

  // ─── Setup parallax for text overlays ───────────────────────
  function setupParallax(actEl, actNum) {
    if (!gsapReady || prefersReducedMotion || isMobile) return;

    const overlay = actEl.querySelector('.act__overlay');
    const chapter = actEl.querySelector('.act__chapter');
    const narrative = actEl.querySelector('.act__narrative');

    if (!overlay) return;

    // Subtle vertical parallax on overlay content
    const items = [chapter, narrative].filter(Boolean);
    items.forEach((item, i) => {
      const depth = (i + 1) * 15;
      const tw = gsap.to(item, {
        y: -depth,
        ease: 'none',
        scrollTrigger: {
          trigger: actEl,
          start: 'top bottom',
          end: 'bottom top',
          scrub: 1,
        },
      });
      if (tw.scrollTrigger) triggers.push(tw.scrollTrigger);
    });
  }

  // ─── Destroy all triggers & observers (SPA view teardown) ──
  function destroy() {
    triggers.forEach(st => st.kill());
    triggers = [];
    loopObservers.forEach(ob => ob.disconnect());
    loopObservers = [];
  }

  // ─── Initialize all acts ───────────────────────────────────
  function init() {
    // Idempotent: clean up any previous build first
    destroy();

    if (!initGSAP()) {
      // GSAP not loaded — fallback to loop-only mode
      console.warn('[scroll-video] GSAP not loaded, using fallback loop mode');
    }

    const acts = document.querySelectorAll('[data-act]');
    acts.forEach(actEl => {
      const actNum = parseInt(actEl.dataset.act, 10);
      const video = actEl.querySelector('.act__video');

      if (video) {
        if (scrubActs.has(actNum)) {
          setupScrubVideo(actEl, video, actNum);
        } else if (loopActs.has(actNum)) {
          setupLoopVideo(video);
        }
      }

      setupParallax(actEl, actNum);
    });
  }

  // Refresh ScrollTrigger after all images/videos load (one-time listener)
  window.addEventListener('load', () => {
    if (gsapReady) ScrollTrigger.refresh();
  });

  // ─── Cleanup on theme/reveal transitions ──────────────────
  function refresh() {
    if (gsapReady) ScrollTrigger.refresh();
  }
  window.addEventListener('app:reveal-done', refresh);

  // ─── SPA 暴露：router 驱动 home 视图的构建与销毁 ───────────
  window.MarsScrollVideo = { init: init, destroy: destroy };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
