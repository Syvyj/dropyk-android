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

  // The story on the TV, over and over while the hero is on screen: a drop leaves the phone,
  // lands in the TV, the TV receives the file and offers to play it, then waits again.
  const devices = document.querySelector('.devices');
  const at = (sel, fx, fy) => {
    const d = devices.getBoundingClientRect(), r = document.querySelector(sel).getBoundingClientRect();
    return { x: r.left - d.left + r.width * fx - 17, y: r.top - d.top + r.height * fy - 24 };
  };
  const from = () => at('.phone', .5, .3), to = () => at('.tv-screen', .5, .5);
  const [receiving, received] = $$('.tv-alt');
  const story = gsap.timeline({ paused: true, repeat: -1, delay: .4 })
    .set('.flyer', { x: () => from().x, y: () => from().y, scale: .6, opacity: 0 }, 2.4)
    .to('.flyer', { x: () => to().x, duration: .9, ease: 'none' }, 2.4)
    .to('.flyer', { keyframes: [
      { y: () => Math.min(from().y, to().y) - 70, scale: 1, opacity: 1, duration: .45, ease: 'power2.out' },
      { y: () => to().y, scale: .4, opacity: 0, duration: .45, ease: 'power2.in' },
    ] }, 2.4)
    .to(receiving, { opacity: 1, duration: .4 }, 3.2)
    .to(received, { opacity: 1, duration: .4 }, 5.8)
    .to([received, receiving], { opacity: 0, duration: .6 }, 8.6);
  ScrollTrigger.addEventListener('refresh', () => story.invalidate());
  let heroOn = true;
  const run = () => (heroOn && intro.progress() === 1 && !document.hidden ? story.play() : story.pause());
  intro.eventCallback('onComplete', run);
  ScrollTrigger.create({ trigger: hero, start: 'top bottom', end: 'bottom top', onToggle: (self) => { heroOn = self.isActive; run(); } });
  document.addEventListener('visibilitychange', run);

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
  // How it works: the steps come in one by one, their numbers pop, their icons draw themselves.
  const stepsEl = document.querySelector('.steps');
  const stepsTl = gsap.timeline({ paused: true })
    .fromTo('.steps li', { opacity: 0, y: IN.y }, { ...IN, opacity: 1, y: 0, stagger: .12 })
    .from('.steps .num', { scale: .4, duration: .5, ease: 'back.out(2)', stagger: .12 }, .2)
    .from('.steps .icon [stroke]', { drawSVG: '0%', duration: 1, ease: 'power2.inOut', stagger: .05,
      // Drawn: hand the strokes back to the plain SVG, exactly as without motion.
      onComplete() { this.targets().forEach((t) => { t.style.strokeDasharray = ''; t.style.strokeDashoffset = ''; }); } }, .3);
  reveals.set(stepsEl, stepsTl);
  ScrollTrigger.create({ trigger: stepsEl, start: 'top 85%', once: true, onEnter: () => stepsTl.play() });
  mm.add(wide, () => {
    gsap.fromTo('.steps-line', { scaleX: 0 }, { scaleX: 1, ease: 'none',
      scrollTrigger: { trigger: '.how', start: 'top 75%', end: 'bottom 60%', scrub: true } });
  });

  // What it does: each row's picture comes in from its own side, uncovering itself; the words follow.
  for (const row of $$('.feature')) {
    const [media, text] = row.children;
    const side = () => (matchMedia(wide).matches ? (media.getBoundingClientRect().left < text.getBoundingClientRect().left ? -48 : 48) : 0);
    const tl = gsap.timeline({ paused: true });
    if (media.classList.contains('where')) {
      gsap.set(media, { opacity: 1 });
      tl.fromTo(media.children, { opacity: 0, x: side, y: () => (side() ? 0 : IN.y) }, { ...IN, opacity: 1, x: 0, y: 0, stagger: .12 });
    } else {
      tl.fromTo(media, { opacity: 0, x: side, y: () => (side() ? 0 : IN.y) }, { ...IN, opacity: 1, x: 0, y: 0 });
      if (media.tagName === 'IMG') {
        tl.fromTo(media, { clipPath: () => (side() > 0 ? 'inset(0 0 0 100%)' : 'inset(0 100% 0 0)') },
          { clipPath: 'inset(0% 0% 0% 0%)', duration: 1, ease: 'power3.inOut', clearProps: 'clipPath' }, 0);
      }
    }
    tl.fromTo(text, { opacity: 0, y: IN.y }, { ...IN, opacity: 1, y: 0 }, .15);
    // The Quick Share prompt: someone presses Accept.
    if (media.classList.contains('qs-card')) {
      tl.to(media.querySelector('.on'), { scale: .94, duration: .15, ease: 'power2.in' }, .9)
        .to(media.querySelector('.on'), { scale: 1, duration: .3, ease: 'back.out(3)' })
        .fromTo(media, { y: 0 }, { y: -6, duration: .15, yoyo: true, repeat: 1, ease: 'power1.out' }, '<');
    }
    reveals.set(row, tl);
    ScrollTrigger.create({ trigger: row, start: 'top 85%', once: true, onEnter: () => tl.play() });
  }

  // Dropyk for Mac: the icon appears with its card, then floats.
  const macIcon = document.querySelector('.mac img');
  const macTl = gsap.timeline({ paused: true })
    .fromTo(macIcon, { opacity: 0, scale: .9 }, { opacity: 1, scale: 1, duration: .8, ease: 'back.out(1.6)' })
    .add(() => gsap.to(macIcon, { yPercent: -5, duration: 3, ease: 'sine.inOut', yoyo: true, repeat: -1 }));
  reveals.set(macIcon, macTl);
  ScrollTrigger.create({ trigger: '.mac', start: 'top 85%', once: true, onEnter: () => macTl.play() });

  // A pointer (or the keyboard) on a card lifts it a little.
  mm.add(hover, () => {
    const off = [];
    for (const card of $$('.more-card, .private li, .install li, .give')) {
      const up = () => { card.classList.add('lifted'); gsap.to(card, { y: -4, duration: .25, ease: 'power2.out' }); };
      const down = () => { card.classList.remove('lifted'); gsap.to(card, { y: 0, duration: .25, ease: 'power2.out' }); };
      for (const [ev, fn] of [['pointerenter', up], ['pointerleave', down], ['focusin', up], ['focusout', down]]) {
        card.addEventListener(ev, fn); off.push(() => card.removeEventListener(ev, fn));
      }
    }
    // Big buttons lean towards the pointer and spring back.
    for (const btn of $$('.btn:not(.btn-small)')) {
      const bx = gsap.quickTo(btn, 'x', { duration: .3, ease: 'power3.out' });
      const by = gsap.quickTo(btn, 'y', { duration: .3, ease: 'power3.out' });
      const move = (e) => {
        const r = btn.getBoundingClientRect();
        bx(gsap.utils.clamp(-6, 6, (e.clientX - r.left - r.width / 2) * .15));
        by(gsap.utils.clamp(-6, 6, (e.clientY - r.top - r.height / 2) * .3));
      };
      const leave = () => gsap.to(btn, { x: 0, y: 0, duration: .8, ease: 'elastic.out(1, .4)' });
      btn.addEventListener('pointermove', move); btn.addEventListener('pointerleave', leave);
      off.push(() => { btn.removeEventListener('pointermove', move); btn.removeEventListener('pointerleave', leave); });
    }
    return () => off.forEach((f) => f());
  });

  // The menu steps aside while you read down and comes back on the way up; it marks the section you're in.
  const nav = document.querySelector('.nav');
  const hideNav = gsap.to(nav, { yPercent: -100, duration: .3, ease: 'power2.out', paused: true });
  ScrollTrigger.create({ start: 0, end: 'max', onUpdate: (self) => {
    nav.classList.toggle('scrolled', self.scroll() > 8);
    if (self.direction > 0 && self.scroll() > 120 && !nav.contains(document.activeElement)) hideNav.play();
    else if (self.direction < 0) hideNav.reverse();
  } });
  nav.addEventListener('focusin', () => hideNav.reverse());
  for (const id of ['how', 'install']) {
    const link = nav.querySelector(`nav a[href="#${id}"]`);
    ScrollTrigger.create({ trigger: `#${id}`, start: 'top center', end: 'bottom center',
      onToggle: (self) => link.classList.toggle('is-here', self.isActive) });
  }
  // Light or dark: the half-moon turns over.
  document.querySelector('.theme').addEventListener('click', () => {
    gsap.fromTo('.theme svg', { rotation: 0 }, { rotation: 180, duration: .5, ease: 'power2.inOut' });
  });

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
