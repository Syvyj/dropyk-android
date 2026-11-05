<div align="center">

**English** · [Українська](README.uk.md)

<img src="web/assets/icon-180.png" width="96" alt="">

# Dropyk

**From your phone to the TV in one scan.**

Open Dropyk on an Android TV or Google TV, point your phone's camera at the code and pick files.
Videos, photos, APKs and links go straight over your home Wi‑Fi — no app on the phone, no cloud, no account.

### [Download the APK](https://github.com/Syvyj/dropyk-android/releases/latest/download/dropyk.apk) · [dropyk.syvyj.dev](https://dropyk.syvyj.dev/)

`Android TV / Google TV` · `Android 7.0+` · `~1 MB` · `GPL-3.0`

<img src="web/assets/en-tv-waiting.webp" width="68%" alt="Dropyk on a TV: a QR code and two steps"> <img src="web/assets/en-phone.webp" width="22%" alt="The page on the phone">

</div>

## How it works

1. **Scan.** Dropyk runs a small web server on the TV. The QR code leads your phone to a page the TV serves itself. The address is stable, so a bookmark keeps working.
2. **Send.** Pick one file or a hundred. They go one by one, straight to the TV's storage, so a 4 GB film never passes through its memory. A link works too.
3. **Press OK.** The TV offers what to do: a video plays, an APK installs, a photo shows on screen, a link opens.

On a budget Google TV box over Wi‑Fi, files arrive at about 12–20 MB/s.

## Where files go

You choose on the phone page, and the phone remembers:

- **Downloads** — everything in `Download/Dropyk`.
- **By type** — videos in `Movies/Dropyk`, photos in `Pictures/Dropyk`, music in `Music/Dropyk`, the rest in `Download/Dropyk`.

No storage permission is needed on Android 10+. On Android 7–9 files go to the app's own folder.

## Privacy

- Files go from the phone to the TV over your own network. There is no server in between and nothing is uploaded anywhere.
- The page address carries a secret key, so only someone who saw the code can send.
- The TV listens only while Dropyk is open. No account, no ads, no tracking.

## Install

1. Download [`dropyk.apk`](https://github.com/Syvyj/dropyk-android/releases/latest/download/dropyk.apk).
2. Get it onto the TV: the Downloader app, a USB stick, or `adb install dropyk.apk`.
3. Allow installing from that source when the TV asks, then open Dropyk from the app list.

## Coming next

- The TV in the **Quick Share** menu of Android phones.
- Sending from a Mac with **Dropyk for Mac**.
- A share-sheet **shortcut for iPhone**.

## Building

```
./gradlew :app:assembleRelease
```

Release signing reads `DROPYK_KEYSTORE`, `DROPYK_KEYSTORE_PASSWORD`, `DROPYK_KEY_ALIAS` and `DROPYK_KEY_PASSWORD` from `.env` or `-P`; without them the release build is debug-signed.

The site lives in [`web/`](web/): `python3 web/build.py` writes the English and Ukrainian pages.

## Support

Dropyk is free and will stay free. [Ko-fi](https://ko-fi.com/syvyj) · [Buy Me a Coffee](https://buymeacoffee.com/syvyj) · [Monobank jar](https://send.monobank.ua/jar/5juJPj142b)

## License

[GPL-3.0](LICENSE).
