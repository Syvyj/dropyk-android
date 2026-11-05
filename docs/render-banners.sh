#!/bin/sh
# Renders the README banners (docs/banner-*.png), the donate buttons (docs/support-*.png) and the social previews (web/assets/social-*.png)
# from docs/banner.html with headless Chrome. Run from the repo root after the screenshots change.
set -e
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
cd "$(dirname "$0")/.."
python3 -m http.server 8799 -b 127.0.0.1 >/dev/null 2>&1 & SERVER=$!
trap 'kill $SERVER' EXIT
sleep 1
shot() { "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=$2,$3 \
  --virtual-time-budget=3000 --screenshot="$4" "http://127.0.0.1:8799/docs/banner.html?lang=$1&w=$2&h=$3" 2>/dev/null; }
for lang in en uk; do
  shot $lang 1600 560 docs/banner-$lang.png
  shot $lang 1280 640 web/assets/social-$lang.png
  for id in kofi bmc mono; do
    "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=560,150 \
      --default-background-color=00000000 --virtual-time-budget=3000 --screenshot="docs/support-$id-$lang.png" \
      "http://127.0.0.1:8799/docs/support.html?id=$id&lang=$lang" 2>/dev/null
  done
done
echo "rendered docs/banner-{en,uk}.png, web/assets/social-{en,uk}.png and docs/support-*.png"
