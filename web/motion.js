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

  const hover = '(hover: hover) and (pointer: fine)';
  const wide = '(min-width: 921px)';
  const mm = gsap.matchMedia();

  // Hero: the copy rises line by line, the TV and the phone slide into place.
  const hero = document.querySelector('.hero');
  const intro = gsap.timeline({ defaults: { duration: .9, ease: 'power3.out' } })
    .fromTo('.hero .pill', { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: .6 }, 0)
    .set('.hero h1', { opacity: 1 }, .1)
    .fromTo('.hero .lead', { opacity: 0, y: 16 }, { opacity: 1, y: 0 }, .45)
    .fromTo('.hero .cta', { opacity: 0, y: 16 }, { opacity: 1, y: 0 }, .55)
    .fromTo('.hero .tv', { opacity: 0, y: 40, scale: .96 }, { opacity: 1, y: 0, scale: 1, duration: 1.1 }, .15)
    .fromTo('.hero .phone', { opacity: 0, x: 40, y: 30, rotation: 4 }, { opacity: 1, x: 0, y: 0, rotation: 0, duration: 1.1 }, .35);
  // Words, not letters: Ukrainian headings are long, and the line breaks must stay where the browser put them.
  SplitText.create('.hero h1', {
    type: 'words', mask: 'words', aria: 'auto', autoSplit: true,
    onSplit: (self) => gsap.from(self.words, { yPercent: 110, duration: .8, ease: 'power3.out', stagger: .04, delay: .1 }),
  });

  // The circle drifts on its own; on wide screens the hero's layers scroll at different speeds.
  gsap.to('.blob', { x: '2vw', y: '2vw', scale: 1.06, duration: 12, ease: 'sine.inOut', yoyo: true, repeat: -1 });
  mm.add(wide, () => {
    const scroll = { trigger: hero, start: 'top top', end: 'bottom top', scrub: true };
    gsap.to('.devices', { yPercent: -6, ease: 'none', scrollTrigger: scroll });
    gsap.to('.phone', { yPercent: -12, ease: 'none', scrollTrigger: { ...scroll } });
    gsap.to('.blob', { yPercent: 30, ease: 'none', scrollTrigger: { ...scroll } });
  });

  // The devices lean towards the pointer, a little.
  mm.add(hover, () => {
    const tilt = document.querySelector('.devices-tilt');
    const area = document.querySelector('.devices');
    gsap.set(tilt, { transformPerspective: 900 });
    const rx = gsap.quickTo(tilt, 'rotationX', { duration: .6, ease: 'power3.out' });
    const ry = gsap.quickTo(tilt, 'rotationY', { duration: .6, ease: 'power3.out' });
    const move = (e) => {
      const r = area.getBoundingClientRect();
      ry(((e.clientX - r.left) / r.width - .5) * 12);
      rx(-((e.clientY - r.top) / r.height - .5) * 12);
    };
    const leave = () => { rx(0); ry(0); };
    area.addEventListener('pointermove', move);
    area.addEventListener('pointerleave', leave);
    return () => { area.removeEventListener('pointermove', move); area.removeEventListener('pointerleave', leave); leave(); };
  });

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
