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


if __name__ == "__main__":
    checks = [v for k, v in sorted(globals().items()) if k.startswith("check_")]
    for c in checks:
        c()
    print(f"ok: {len(checks)} checks")
