# Off-Book+: A Local Media Player for Wear OS

Off-Book+ is a standalone, local-first audiobook, podcast, and music player designed specifically for Wear OS. It allows you to sideload your own audio files and enjoy them on the go, completely offline. The player is built with a modern, database-backed architecture using Jetpack Compose, Media3, and Room.

## Key Features

-   **100% Offline, Local Playback:** No streaming, no internet required. Play files directly from your watch's storage.
-   **Self-Update from GitHub:** Easily check for, download, and install the latest APK directly from the GitHub releases page, bypassing the Google Play Store for rapid updates.
-   **Multi-Type Library:** Organizes your media into three distinct collections: **Audiobooks**, **Podcasts**, and **Music**.
-   **Folder-Based Playlists:** Automatically treats each folder within your media directories as a playlist, enabling continuous, sequential playback of all tracks within it.
-   **Playback Customization:**
    *   **Variable Speed Control:** User-configurable playback speed from **1.0x to 3.0x**, saved per media type.
    *   **Advanced Seek Gestures:** Utilizes combined-click listeners on the Player screen for intuitive seeking:
        *   **Single-tap:** Small jump (+30s / -15s).
        *   **Double-tap:** Medium jump (+60s / -60s).
        *   **Long-press:** Large skip (+15m / -15m).
    *   **Shuffle Support:** Toggle shuffle mode on/off for Music playlists.
-   **Persistent Library:** Your library is scanned and saved to a local database, ensuring fast, instant loading of your collections.
-   **Conditional Progress Saving:** Playback position is intelligently saved **only for Audiobooks**, allowing you to pick up exactly where you left off. Music and podcasts always start from the beginning of the track/playlist.
-   **Manual Library Management:** A dedicated settings screen gives you full control to trigger a manual rescan of your device's storage.
-   **Modern Architecture:** Built on Media3 (ExoPlayer) with audio offload support, ensuring robust and battery-efficient background playback.

## How It Works

The application is built on a modern Android tech stack:
-   **UI:** Jetpack Compose for Wear OS with Horologist components.
-   **Playback:** Media3, using a foreground `MediaSessionService` to handle background playback and `ExoPlayer` as the underlying engine.
-   **Database:** Room Persistence Library to store the media library and audiobook progress.
-   **Architecture:** Follows MVVM principles, with ViewModels driving UI state and logic.
-   **Concurrency:** Kotlin Coroutines are used for all background tasks, including file scanning and update checks.

## Getting Started & Usage

To use Off-Book+, you must manually copy audio files to your watch.

1.  **Connect to your watch via ADB:**
    ```bash
    adb connect <your_watch_ip_address>:5555
    ```

2.  **Create the necessary directories** on your watch's internal storage (`/sdcard/`):
   -   `/sdcard/Audiobooks/`
   -   `/sdcard/Podcasts/`
   -   `/sdcard/Music/`

3.  **Push your files using `adb push`**. Place related files (e.g., chapters of a book, songs of an album) inside their own sub-folder.

    **Example:**
    ```bash
    # Push an audiobook with multiple chapters
    adb push "path/to/My Awesome Book" "/sdcard/Audiobooks/"

    # Push a music album
    adb push "path/to/My Favorite Album" "/sdcard/Music/"
    ```
    The app will treat the `My Awesome Book` folder as a single playlist.

4.  **Scan Your Library:**
   -   Open the Off-Book+ app on your watch.
   -   Navigate to **Settings**.
   -   Tap **"Force Full Rescan"**.
   -   The app will scan the directories, save all found items to its database, and you can then navigate to your collections to see your media.

### Application Self-Update

Once the application is installed, you can use the built-in update checker:
1.  Navigate to **Settings**.
2.  Tap **"Check for App Update"**.
3.  If an update is available, the button text will change to indicate a new version. Tapping it again will download the APK and automatically prompt you to install it.

## Building

No JDK or Android SDK is needed on this machine: builds run as one-shot
Kubernetes Jobs on the local k3s cluster (the colima VM with the
`docker+k3s` runtime), driven by tooling that lives in [`k8s/`](k8s/README.md).

```bash
# one-time: build the JDK21+SDK toolchain image into k3s containerd
./k8s/scripts/image.sh

# build (default task: assembleDebug)
./k8s/scripts/build.sh

# signed release APK (keystore creds pass through from local.properties)
./k8s/scripts/build.sh --task assembleRelease
```

Sources are synced to `k8s/.workspace`, mounted into the Job via hostPath,
logs stream to your terminal (`k8s/.cache/last-build.log` keeps the last),
and APK/AAB outputs are copied back into `app/build/outputs`. Nothing runs
between builds — each build is a short-lived Job.

### Testing on a Wear OS watch

The same cluster can install and exercise builds on a physical watch over
Wi-Fi ADB (in-cluster emulation is not possible under colima on Apple
Silicon — no nested KVM):

```bash
./k8s/scripts/deploy.sh --ip <watch-ip> --launch      # install + open app
./k8s/scripts/deploy.sh --ip <watch-ip> --action shot # screenshot -> k8s/.artifacts/
```

Pairing, logcat and uninstall actions are documented in [k8s/README.md](k8s/README.md).

> Note: `k8s/.workspace`, `k8s/.cache` and `k8s/.artifacts` are runtime dirs
> kept out of git; the CI workflow in `.github/workflows/ci.yml` builds the
> same project on GitHub Actions runners.

## Roadmap & Future Features

This project is in active development. The following features are planned for future releases:

-   **Playback Customization:**
    -   User-configurable default speed setting for each media type.
    -   On-screen volume control.

-   **UI/UX & Data Management:**
    -   A larger, more interactive progress bar on the player screen.
    -   Intelligently filter the library view to only show directories that contain valid media files.
    -   Options to export playback history or backup application data.

-   **Wireless File Transfer:**
    -   Implement a feature to copy files to the watch over Wi-Fi, removing the dependency on ADB for everyday use.
