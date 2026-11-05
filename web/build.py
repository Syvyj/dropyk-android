#!/usr/bin/env python3
"""Builds the site from src/: English at /, Ukrainian at /uk/. In the template, [[English||Українська]]
picks one side per language; {{name}} fills a value. Run: python3 web/build.py
Preview: python3 -m http.server -d web 8090"""
import hashlib
import html
import os
import re

SITE = "https://dropyk.syvyj.dev/"
REPO = "https://github.com/Syvyj/dropyk-android"
# The newest release's APK; the release must carry an asset with exactly this name.
DOWNLOAD = REPO + "/releases/latest/download/dropyk.apk"

# Donation links — the same as Likhtar's and the app's support screen. Monobank first in Ukrainian.
DONATE = [
    ("Ko-fi", "https://ko-fi.com/syvyj", "[[Card or PayPal||Картка чи PayPal]]"),
    ("Buy Me a Coffee", "https://buymeacoffee.com/syvyj", "[[A coffee, any amount||Кава, будь-яка сума]]"),
    ("[[Monobank jar||Банка Monobank]]", "https://send.monobank.ua/jar/5juJPj142b", "[[Card, Apple Pay, Google Pay||Картка, Apple Pay, Google Pay]]"),
]

HERE = os.path.dirname(os.path.abspath(__file__))


def donate_block(lang):
    options = DONATE if lang == "en" else [DONATE[2], DONATE[0], DONATE[1]]
    return "\n".join(
        f'<a class="give" href="{html.escape(url)}" target="_blank" rel="noopener"><b>{name}</b><small>{note}</small>'
        f'<span aria-hidden="true">↗</span></a>'
        for name, url, note in options
    )


def render(template, lang):
    side = 0 if lang == "en" else 1
    page = template.replace("{{donate}}", donate_block(lang))
    page = re.sub(r"\[\[(.*?)\|\|(.*?)\]\]", lambda m: m.group(1 + side), page, flags=re.S)
    root = "" if lang == "en" else "../"
    # A new version of the CSS gets a new URL, so browsers don't keep the old one.
    ver = hashlib.sha1(open(os.path.join(HERE, "style.css"), "rb").read()).hexdigest()[:8]
    values = {
        "ver": ver, "lang": lang, "root": root, "download": DOWNLOAD, "repo": REPO,
        "home": root + ("" if lang == "en" else "uk/"),
        "other": root + ("uk/" if lang == "en" else ""),
        "url": SITE + ("" if lang == "en" else "uk/"),
        "other_lang": "uk" if lang == "en" else "en",
    }
    page = re.sub(r"\{\{(\w+)\}\}", lambda m: values[m.group(1)], page)
    assert "[[" not in page and "{{" not in page, f"unfilled placeholder in {lang}"
    return page


def write(out, text):
    path = os.path.join(HERE, out)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(text)
    print("wrote", out)


template = open(os.path.join(HERE, "src/page.html"), encoding="utf-8").read()
write("index.html", render(template, "en"))
write("uk/index.html", render(template, "uk"))
write("robots.txt", f"User-agent: *\nAllow: /\n\nSitemap: {SITE}sitemap.xml\n")

# Two language pages, each pointing at its sibling, so search engines don't treat them as duplicates.
ALTS = "".join(
    f'\n    <xhtml:link rel="alternate" hreflang="{l}" href="{SITE}{p}"/>'
    for l, p in (("en", ""), ("uk", "uk/"), ("x-default", ""))
)
write("sitemap.xml", '<?xml version="1.0" encoding="UTF-8"?>\n'
      '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
      + "".join(f"  <url>\n    <loc>{SITE}{p}</loc>{ALTS}\n  </url>\n" for p in ("", "uk/"))
      + "</urlset>\n")

# Browsers ask for /favicon.ico on their own. An ICO with a PNG inside: one header over the 32×32 icon.
png = open(os.path.join(HERE, "assets/icon-32.png"), "rb").read()
ico = (0).to_bytes(2, "little") + (1).to_bytes(2, "little") + (1).to_bytes(2, "little") \
    + bytes([32, 32, 0, 0]) + (1).to_bytes(2, "little") + (32).to_bytes(2, "little") \
    + len(png).to_bytes(4, "little") + (22).to_bytes(4, "little") + png
open(os.path.join(HERE, "favicon.ico"), "wb").write(ico)
print("wrote favicon.ico")
