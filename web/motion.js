// Dropyk site motion (GSAP 3.15.0, vendored). Progressive: the page is complete without this file.
// The head script adds .motion unless the visitor asked for less motion, and removes it after 3 s
// if this file hasn't started by then — so a blocked or slow script never leaves content invisible.
(() => {
  const root = document.documentElement;
  const ready = !!window.gsap && root.classList.contains('motion');
  if (!ready) return;
  window.dropykMotion = true;
  gsap.registerPlugin(ScrollTrigger, SplitText, DrawSVGPlugin);
})();
