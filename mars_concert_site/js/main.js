/* ════════════════════════════════════════════════════════════
   MARS CONCERT 2.0 · main.js
   主入口：滚动进度、幕激活、视频懒加载、初始化
   ════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  // ─── Global state ──────────────────────────────────────────
  const state = {
    currentAct: -1,
    acts: [],
    isMobile: window.matchMedia('(max-width: 767px)').matches,
    prefersReducedMotion: window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  };

  // ─── Scroll progress bar ───────────────────────────────────
  const progressBar = document.getElementById('scrollProgress');

  function updateScrollProgress() {
    const scrollTop = window.scrollY || document.documentElement.scrollTop;
    const scrollHeight = document.documentElement.scrollHeight - window.innerHeight;
    const progress = scrollHeight > 0 ? (scrollTop / scrollHeight) * 100 : 0;
    progressBar.style.width = progress + '%';
  }

  // ─── Collect all act sections ──────────────────────────────
  function collectActs() {
    state.acts = Array.from(document.querySelectorAll('[data-act]')).map(el => ({
      el: el,
      id: el.id,
      actNum: parseInt(el.dataset.act, 10),
      video: el.querySelector('.act__video'),
    }));
  }

  // ─── IntersectionObserver: activate act on scroll ──────────
  let actObserver = null;

  function setupActObserver() {
    const threshold = state.isMobile ? 0.3 : 0.5;

    actObserver = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting && entry.intersectionRatio >= threshold) {
          const actNum = parseInt(entry.target.dataset.act, 10);
          setActiveAct(actNum);
        }
      });
    }, {
      threshold: [threshold],
      rootMargin: '-10% 0px -10% 0px',
    });

    state.acts.forEach(act => actObserver.observe(act.el));
  }

  function setActiveAct(actNum) {
    if (state.currentAct === actNum) return;
    state.currentAct = actNum;

    // Mark current act as active for CSS entrance animations
    state.acts.forEach(act => {
      act.el.classList.toggle('is-active', act.actNum === actNum);
    });

    // Update nav dots
    document.querySelectorAll('.nav-dot').forEach(dot => {
      dot.classList.toggle('active', dot.dataset.target === 'act-' + actNum);
    });

    // Dispatch event for other modules
    window.dispatchEvent(new CustomEvent('act:change', { detail: { actNum } }));
  }

  // ─── Video lazy loading ────────────────────────────────────
  function setupVideoLazyLoad() {
    const videoObserver = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          const video = entry.target;
          const source = video.querySelector('source[data-src]');
          if (source && !source.src) {
            source.src = source.dataset.src;
            video.load();
          }
          // Mark as loaded when video data is ready
          video.addEventListener('loadeddata', () => {
            video.classList.add('is-loaded');
          }, { once: true });
          videoObserver.unobserve(video);
        }
      });
    }, { rootMargin: '200px 0px' });

    state.acts.forEach(act => {
      if (act.video) videoObserver.observe(act.video);
    });

    // Also lazy-load finale player
    const finaleVideo = document.querySelector('.finale-player__video');
    if (finaleVideo) {
      videoObserver.observe(finaleVideo);
      finaleVideo.addEventListener('loadeddata', () => {
        finaleVideo.classList.add('is-loaded');
      }, { once: true });
    }
  }

  // ─── Finale card observer ──────────────────────────────────
  function setupFinaleObserver() {
    const finaleCard = document.getElementById('finale-card');
    if (!finaleCard) return;

    const observer = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting && entry.intersectionRatio >= 0.4) {
          finaleCard.classList.add('is-active');
        }
      });
    }, { threshold: [0.4] });

    observer.observe(finaleCard);
  }

  // ─── Finale player auto-play ────────────────────────────────
  function setupFinalePlayer() {
    const finaleVideo = document.querySelector('.finale-player__video');
    if (!finaleVideo) return;

    const observer = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
          // Auto-play the promo film when scrolled into view
          finaleVideo.play().catch(() => {
            // Autoplay blocked — user can click play
          });
        } else {
          finaleVideo.pause();
        }
      });
    }, { threshold: [0.5] });

    observer.observe(finaleVideo);
  }

  // ─── Poster full-res swap ───────────────────────────────────
  function setupPosterSwap() {
    const videos = document.querySelectorAll('.act__video[data-poster-full]');
    const observer = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          const video = entry.target;
          const fullPoster = video.dataset.posterFull;
          if (fullPoster && !video.dataset.posterSwapped) {
            const img = new Image();
            img.onload = () => {
              video.poster = fullPoster;
              video.dataset.posterSwapped = '1';
            };
            img.src = fullPoster;
          }
          observer.unobserve(video);
        }
      });
    }, { rootMargin: '100px 0px' });

    videos.forEach(v => observer.observe(v));
  }

  // ─── HERO entrance animation ───────────────────────────────
  function triggerHeroAnimation() {
    const heroContent = document.querySelector('.hero-content');
    if (heroContent) {
      requestAnimationFrame(() => {
        heroContent.classList.add('is-visible');
      });
    }
  }

  // ─── Scroll handler (throttled via rAF) ────────────────────
  let scrollTicking = false;

  function onScroll() {
    if (!scrollTicking) {
      requestAnimationFrame(() => {
        updateScrollProgress();
        scrollTicking = false;
      });
      scrollTicking = true;
    }
  }

  // ─── Init ──────────────────────────────────────────────────
  function init() {
    collectActs();
    setupActObserver();
    setupVideoLazyLoad();
    setupFinaleObserver();
    setupFinalePlayer();
    setupPosterSwap();
    triggerHeroAnimation();

    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', () => {
      state.isMobile = window.matchMedia('(max-width: 767px)').matches;
    }, { passive: true });

    updateScrollProgress();
    setActiveAct(0);

    // Dispatch init event for other modules
    window.dispatchEvent(new CustomEvent('app:ready', { detail: state }));

    console.log('%c MARS CONCERT 2.0 ', 'background:#0A0A0F;color:#E63946;font-weight:bold;padding:4px 12px;border-radius:2px;');
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  // Expose state for debugging
  window.MarsConcert = { state, setActiveAct };
})();
