#!/usr/bin/env python3
"""Checks the built site (run after build.py): the vendored GSAP, the script tags, no CDNs, the failsafe.
Run: python3 web/build.py && python3 web/check.py"""
import hashlib
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
GSAP = "vendor/gsap/3.15.0/"
PLUGINS = ["gsap", "ScrollTrigger", "SplitText", "DrawSVGPlugin"]
PAGES = {"index.html": "", "uk/index.html": "../"}


def read(name):
    return open(os.path.join(HERE, name), encoding="utf-8").read()


def head(page):
    return page.split("</head>")[0]


def check_vendor():
    for p in PLUGINS:
        path = os.path.join(HERE, GSAP + p + ".min.js")
        assert os.path.isfile(path), f"missing {path}"
        top = open(path, "rb").read(200)
        assert (b"GSAP" if p == "gsap" else p.encode()) + b" 3.15.0" in top and b"standard-license" in top, f"{p}: not 3.15.0 with its license"


def check_pages():
    ver = hashlib.sha1(open(os.path.join(HERE, "motion.js"), "rb").read()).hexdigest()[:8]
    for name, root in PAGES.items():
        srcs = re.findall(r'<script defer src="([^"]+)"></script>', read(name))
        want = [f"{root}{GSAP}{p}.min.js" for p in PLUGINS] + [f"{root}motion.js?v={ver}"]
        assert srcs == want, f"{name}: scripts {srcs}, want {want}"


def check_no_cdn():
    for name in [*PAGES, "motion.js"]:
        text = read(name)
        for bad in ("cdn.", "unpkg", "jsdelivr", "googleapis"):
            assert bad not in text, f"{name}: {bad}"


def check_failsafe():
    for name in PAGES:
        h = head(read(name))
        for part in ("classList.add('motion')", "prefers-reduced-motion", "setTimeout(", "3000"):
            assert part in h, f"{name}: failsafe lacks {part}"



REVEALS = {"strip", "how-title", "features-title", "more", "mac", "install", "private", "support"}


def check_reveals():
    for name in PAGES:
        page = read(name)
        groups = re.findall(r'data-reveal="([\w-]+)"', page)
        assert set(groups) == REVEALS, f"{name}: data-reveal groups {sorted(set(groups))}"
        assert not re.search(r'<section class="hero"[^>]*data-reveal', page), f"{name}: hero must not be data-reveal"
        steps = page.split('<ol class="steps">')[1].split("</ol>")[0]
        assert "data-reveal" not in steps, f"{name}: steps belong to Task 5"


def check_hero():
    for name in PAGES:
        page = read(name)
        body = page.split("<body>")[1].lstrip()
        assert body.startswith('<div class="backdrop" aria-hidden="true"><div class="blob"></div></div>'), f"{name}: .backdrop > .blob must open <body>"
        devices = page.split('<div class="devices">')[1].split("</section>")[0]
        tilt = devices.split('<div class="devices-tilt">')
        assert len(tilt) == 2 and '<figure class="tv">' in tilt[1] and '<figure class="phone">' in tilt[1], f"{name}: .devices-tilt must wrap .tv and .phone"
    css = read("style.css")
    assert "radial-gradient(circle at 96% 4%" not in css, "the circle moved from body to .blob"
    assert re.search(r"\.backdrop \{[^}]*overflow: hidden", css), ".backdrop must clip the circle: no sideways scrolling"


def check_story():
    for name, root in PAGES.items():
        page = read(name)
        lang = "en" if root == "" else "uk"
        screen = page.split('<div class="tv-screen">')[1].split("</div>")[0]
        alts = re.findall(r'<img class="tv-alt" src="([^"]+)" alt="" aria-hidden="true"', screen)
        want = [f"{root}assets/{lang}-tv-receiving.webp", f"{root}assets/{lang}-tv-received-video.webp"]
        assert alts == want, f"{name}: tv-alt images {alts}, want {want}"
        for a in alts:
            assert os.path.isfile(os.path.join(HERE, a.replace("../", ""))), f"missing {a}"
        assert '<svg class="flyer" aria-hidden="true"><use href="#drop"/></svg>' in page, f"{name}: no .flyer"


def check_steps():
    for name in PAGES:
        steps = read(name).split('<ol class="steps">')[1].split("</ol>")[0]
        assert steps.lstrip().startswith('<span class="steps-line" aria-hidden="true"></span>'), f"{name}: .steps-line must open .steps"
        assert steps.count("<li>") == 3, f"{name}: three steps"
    css = read("style.css")
    assert re.search(r"\.steps-line \{[^}]*display: none", css) and re.search(r"min-width: 921px\) \{[^}]*\.steps-line \{ display: block", css), ".steps-line only on wide screens"


def check_hidden_only_under_motion():
    css = read("style.css")
    rule = re.search(r"\n(\.motion [^{]+)\{ opacity: 0; \}", css)
    assert rule, "one .motion hide rule"
    sels = [s.strip() for s in rule.group(1).split(",")]
    assert all(s.startswith(".motion ") for s in sels), sels
    for want in (".motion .feature > *", ".motion .mac img"):
        assert want in sels, f"hide rule lacks {want}"
    assert css.count("opacity: 0") == css.count("{ opacity: 0; }") + 2, "no other unconditional opacity: 0 besides .tv-alt and .flyer"

if __name__ == "__main__":
    checks = [v for k, v in sorted(globals().items()) if k.startswith("check_")]
    for c in checks:
        c()
    print(f"ok: {len(checks)} checks")
