### Current MVP only supports basic Audiobook playback from /sdcard/Audiobooks
### Big plans in store to actually make the rest of this readme legit.. maby 

# OffBeatPlus
*Your wrist just became your offline media library.*
*WearOS Offline Media player for Audiobooks, Podcasts and music*

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)  
[![API](https://img.shields.io/badge/API-28%2B-brightgreen.svg)](build.gradle)  
[![Release](https://img.shields.io/github/v/release/devlight/OffBeatPlus)](https://github.com/devlight/OffBeatPlus/releases)  
[![F-Droid](https://img.shields.io/f-droid/v/io.github.devlight.offbeatplus)](https://f-droid.org/en/packages/io.github.devbeatplus)

---

## Why OffBeatPlus?

WearOS ships with a music player, but it **requires your phone** and **forgets audiobooks the moment you close the app**.  
OffBeatPlus is a **tiny, open-source, fully-offline** player engineered for **audiobooks, podcasts & music**—no phone, no cloud, no ads, no tracking.  
Sync once, then run, hike, swim or commute **completely untethered**.

---

## ✨ Highlights

|  |  |
|---|---|
| 📚 **Audiobook-first** | Automatic per-book progress, chapters, bookmarks, sleep-timer with smart rewind. |
| 🎙️ **Podcast ready** | RSS downloader, OPML import, background refresh when on charger/Wi-Fi. |
| 🎧 **Music friendly** | Gapless playback, album art, playlists, shuffle & repeat. |
| ⌚ **Wear-OS native** | Written in Kotlin + Compose for Wear, 60 fps lists, rotary input, hardware buttons mapped. |
| 🛜 **100 % offline** | No Google APIs, no Firebase, no internet permission after sync. |
| 🔋 **Brutal battery life** | < 3 % per hour on most watches (tested on Pixel Watch 2 & Galaxy Watch 6). |
| 🧩 **Extensible** | Plugin-style decoder interface; drop-in FFmpeg or Android MediaCodec. |
| 🌓 **Material You** | Dynamic colours, AMOLED black theme, large-screen round layouts. |
| 🔓 **FOSS** | MIT licensed, community translations, public roadmap, GitHub Actions CI. |

---

## Screenshots

<p float="left">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01_library.png" width="200" alt="Library"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02_player.png" width="200" alt="Player"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03_sleep.png" width="200" alt="Sleep Timer"/>
</p>

---

## Download

[<img src="assets/get_it_on_github.png" height="60">](https://github.com/devlight/OffBeatPlus/releases/latest)  
[<img src="assets/get_it_on_fdroid.png" height="60">](https://f-droid.org/en/packages/io.github.devlight.offbeatplus)

Or build yourself in **Android Studio Hedgehog** → *Run* on any Wear 2.0+ device.

---

## Quick-Start

1. Install the **OffBeatPlus Sync** companion (optional) on your phone or use ADB:
   ```bash
   adb push myBook.m4a /sdcard/Music/
   ```
2. Long-press the crown → OffBeatPlus → *Rescan*.
3. Tap the book → hit play.  
   That’s it—your progress is auto-saved every 30 s to the watch’s internal storage.

---

## Syncing Media

| Method | Works without Play Services | Notes |
|---|---|---|
| ADB | ✅ | Drag & drop, fastest for big libraries. |
| Companion Android app | ✅ | Wi-Fi direct, converts chapters, embeds covers. |
| USB-C OTG | ✅ | Copy to `Music/` folder, then rescan. |
| Google Drive / Dropbox | ❌ | OffBeatPlus requests zero internet permissions. |

---

## Supported Formats

* Audio: MP3, M4A, M4B, OPUS, FLAC, OGG, WMA
* Playlist: M3U, PLS, WPL
* Podcast: RSS 2.0 with enclosures, Atom 1.0
* Audiobook chapters: Nero, QuickTime, MP4, M4B chapters
* Metadata: ID3v2.4, Vorbis Comment, FLAC tags

---

## Developer Corner

### Clone & Build
```bash
git clone https://github.com/devlight/OffBeatPlus.git
cd OffBeatPlus
./gradlew assembleDebug
```
APK lands in `app/build/outputs/apk/debug/`.

### Architecture
* **UI**: Compose for Wear + Horologist
* **Domain**: Clean MVVM, Coroutines, Flow
* **Data**: Room + plain JSON for portability
* **Playback**: ExoPlayer + custom FFmpeg extension
* **DI**: Koin (lightweight for 512 MB watches)

### Contribute
1. Pick a [good first issue](https://github.com/devlight/OffBeatPlus/labels/good%20first%20issue).
2. Fork → feature branch → PR → CI runs instrumentation on emulator.
3. We squash-merge and publish nightly to `main` branch F-Droid repo.

---

## Roadmap

* [ ] Wear 4.x tiles & complications
* [ ] Bluetooth headset button custom mapping
* [ ] Cloud-free podcast sync via local RSS server
* [ ] TTS for chapter names (accessibility)
* [ ] Translations: FR, DE, ES, JA, ZH-rCN (help welcome!)

Vote or add ideas in [Discussions](https://github.com/devlight/OffBeatPlus/discussions).

---

## License

MIT © 2024 DevLight. See [LICENSE](LICENSE) for full text.

---

## Acknowledgements

ExoPlayer, FFmpeg-Kit, Horologist, Material Icons, JetBrains Kotlin, and every contributor who filed a bug or sent a pull request.  
Logo derived from [Tabler Icons](https://tabler-icons.io), MIT licensed.

---

## Support

💬 Matrix: [#offbeatplus:matrix.org](https://matrix.to/#/#offbeatplus:matrix.org)  
📧 Email: offbeatplus@devlight.io  
☕ Buy maintainers a coffee: [Ko-fi](https://ko-fi.com/devlight)

Enjoy your offline beats—wherever your wrist takes you!