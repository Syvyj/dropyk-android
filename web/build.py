#!/usr/bin/env python3
"""Builds the site from src/: English at /, Ukrainian at /uk/. In the template, [[English||Українська]]
picks one side per language; {{name}} fills a value. Run: python3 web/build.py
Preview: python3 -m http.server -d web 8090"""
import hashlib
import html
import json
import os
import re
import urllib.request

SITE = "https://dropyk.syvyj.dev/"
REPO = "https://github.com/Syvyj/dropyk-android"
# The newest release's APK; the release must carry an asset with exactly this name.
DOWNLOAD = REPO + "/releases/latest/download/dropyk.apk"
MAC_REPO = "https://github.com/Syvyj/dropyk-mac"
MAC_DOWNLOAD = MAC_REPO + "/releases/latest/download/Dropyk.dmg"
REPO_API = "Syvyj/dropyk-android"
MAC_API = "Syvyj/dropyk-mac"

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


def downloads(repo, names):
    """Downloads of the named release files over all releases (the in-app updaters' too); None if GitHub can't be reached."""
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases?per_page=100",
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "dropyk-site-build"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            releases = json.load(r)
    except (OSError, ValueError) as e:
        print(f"no download count for {repo}: {e}")
        return None
    return sum(a["download_count"] for rel in releases for a in rel["assets"] if a["name"] in names)


def plural_uk(n):
    """завантаження for 1, 2–4 (not 11–14), 21, 22…; завантажень otherwise."""
    return "завантаження" if n % 10 in (1, 2, 3, 4) and n % 100 not in (11, 12, 13, 14) else "завантажень"


def stats_line(lang, stats):
    tv, mac = stats
    if tv is None or mac is None:
        return ""
    b = lambda n: f'<b data-count="{n}">{n}</b>'
    if lang == "en":
        return f'<p class="stats">{b(tv)} {"download" if tv == 1 else "downloads"} for the TV · {b(mac)} for the Mac</p>'
    return f'<p class="stats">{b(tv)} {plural_uk(tv)} для ТВ · {b(mac)} для Mac</p>'


def ver(name):
    """A new version of a file gets a new URL, so browsers don't keep the old one."""
    return hashlib.sha1(open(os.path.join(HERE, name), "rb").read()).hexdigest()[:8]


def render(template, lang, stats=(None, None)):
    side = 0 if lang == "en" else 1
    page = template.replace("{{donate}}", donate_block(lang)).replace("{{stats}}", stats_line(lang, stats))
    page = re.sub(r"\[\[(.*?)\|\|(.*?)\]\]", lambda m: m.group(1 + side), page, flags=re.S)
    root = "" if lang == "en" else "../"
    values = {
        "ver": ver("style.css"), "mver": ver("motion.js"), "lang": lang, "root": root, "download": DOWNLOAD, "repo": REPO,
        "mac_download": MAC_DOWNLOAD, "mac_repo": MAC_REPO,
        # Each language gets its own shortcut: the name it imports with is the file's name.
        "shortcut": root + "shortcut/" + ("Send%20to%20Dropyk.shortcut" if lang == "en" else
                                           "%D0%9D%D0%B0%D0%B4%D1%96%D1%81%D0%BB%D0%B0%D1%82%D0%B8%20%D0%BD%D0%B0%20Dropyk.shortcut"),
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



def main():
    template = open(os.path.join(HERE, "src/page.html"), encoding="utf-8").read()
    # Downloads, counted on GitHub when the site is built: visitors' browsers ask nobody.
    stats = (downloads(REPO_API, ("dropyk.apk",)), downloads(MAC_API, ("Dropyk.dmg", "Dropyk.zip")))
    write("index.html", render(template, "en", stats))
    write("uk/index.html", render(template, "uk", stats))
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


if __name__ == "__main__":
    main()
