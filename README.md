# Off-Book+: A Local Media Player for Wear OS

Off-Book+ is a standalone, local-first audiobook, podcast, and music player designed for Wear OS. It allows you to sideload your own audio files and enjoy them on the go, completely offline. The player is built with a modern, database-backed architecture using Jetpack Compose, Media3, and Room.

## Key Features

-   **100% Offline, Local Playback:** No streaming, no internet required. Play files directly from your watch's storage.
-   **Multi-Type Library:** Organizes your media into three distinct collections: **Audiobooks**, **Podcasts**, and **Music**.
-   **Folder-Based Playlists:** Automatically treats each folder within your media directories as a playlist, enabling continuous, sequential playback of all tracks within it.
-   **Persistent Library:** Your library is scanned and saved to a local database, ensuring fast, instant loading of your collections every time you open the app.
-   **Conditional Progress Saving:** Playback position is intelligently saved **only for Audiobooks**, allowing you to pick up exactly where you left off. Music and podcasts will always start from the beginning.
-   **Manual Library Management:** A dedicated settings screen gives you full control to trigger a manual rescan of your device's storage to discover new media.
-   **Modern Playback Engine:** Built on **Media3 (ExoPlayer)**, providing robust, battery-efficient playback with audio offload support.
-   **Native Wear OS Interface:** A clean, simple UI built with Jetpack Compose, designed for ease of use on a small screen.

## How It Works

The application is built on a modern Android tech stack:
-   **UI:** Jetpack Compose for Wear OS with Horologist components.
-   **Playback:** Media3, using a foreground `MediaSessionService` to handle background playback and `ExoPlayer` as the underlying engine.
-   **Database:** Room Persistence Library to store the media library and audiobook progress.
-   **Architecture:** Follows MVVM principles, with ViewModels driving UI state and logic.
-   **Concurrency:** Kotlin Coroutines are used for all background tasks, including file scanning and database operations.

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
   -   Tap **"Rescan Library"**.
   -   The app will scan the directories, save all found items to its database, and you can then navigate to your collections to see your media.

## Roadmap & Future Features

This project is in active development. The following features are planned for future releases:

-   **Advanced Playback Controls:**
   -   Implement more intuitive seek gestures (e.g., tap-to-increment, double-tap to skip, hold-to-scrub).
   -   A larger, more interactive progress bar.

-   **Playback Customization:**
   -   User-configurable playback speed control, with different defaults for each media type.
   -   On-screen volume control.

-   **Enhanced Library Management:**
   -   Intelligently filter the library view to only show directories that contain valid media files.
   -   Explore integration with the Android MediaStore for more robust file discovery.

-   **UI/UX Overhaul:**
   -   A more polished player screen with better metadata display (e.g., album/book title).
   -   Improved visual feedback for controls.

-   **Data Management:**
   -   Options to export playback history or backup application data.

-   **Wireless File Transfer:**
   -   Implement a feature to copy files to the watch over Wi-Fi, removing the dependency on ADB for everyday use.