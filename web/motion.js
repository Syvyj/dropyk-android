// Dropyk site motion (GSAP 3.15.0, vendored). Progressive: the page is complete without this file.
// The head script adds .motion unless the visitor asked for less motion, and removes it after 3 s
// if this file hasn't started by then — so a blocked or slow script never leaves content invisible.
(() => {
  const root = document.documentElement;
  const ready = !!window.gsap && root.classList.contains('motion');
  if (!ready) return;
  window.dropykMotion = true;
  gsap.registerPlugin(ScrollTrigger, SplitText, DrawSVGPlugin);
  const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];
  const IN = { duration: .8, ease: 'power3.out', y: 28, stagger: .08 };

  // Blocks appear as they scroll in. A group names what inside it comes in one by one; the rest
  // animate as a whole. Opacity only, never visibility: an element not shown yet stays focusable.
  const PARTS = {
    strip: 'li', more: 'h2, .more-card', mac: '.mac > div',
    install: 'h2, li, .small', private: 'h2, li', support: '.support > img, .support > div > *',
  };
  const reveals = new Map();
  for (const el of $$('[data-reveal]')) {
    const parts = PARTS[el.dataset.reveal] ? $$(PARTS[el.dataset.reveal], el) : [el];
    if (parts[0] !== el) gsap.set(el, { opacity: 1 });
    const tl = gsap.timeline({ paused: true }).fromTo(parts, { opacity: 0, y: IN.y }, { ...IN, opacity: 1, y: 0 });
    reveals.set(el, tl);
    ScrollTrigger.create({ trigger: el, start: 'top 85%', once: true, onEnter: () => tl.play() });
  }
  // Already above the screen (an anchor link, a reload halfway down): shown, not replayed.
  const settle = () => reveals.forEach((tl, el) => { if (el.getBoundingClientRect().bottom < 0) tl.progress(1); });
  settle();
  ScrollTrigger.addEventListener('refresh', settle);
  // Tabbing into a block that hasn't appeared yet shows it at once.
  document.addEventListener('focusin', (e) => {
    const el = e.target.closest('[data-reveal]');
    if (el && reveals.has(el)) reveals.get(el).progress(1);
  });
})();
