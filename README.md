# Webnovel Archiver (Android)

Webnovel Archiver is a powerful, local-first Android application designed to download, archive, and export webnovels for offline reading. Built with **React Native** and **Expo**, it brings the power of Python-based scrapers to your mobile device, allowing you to build a personal library of your favorite stories.

## 🚀 Key Features

-   **Webnovel Scraping**: Automatically fetches chapters from supported sites (currently optimized for RoyalRoad).
-   **Offline Reading**: Downloaded chapters are stored locally, accessible anytime without an internet connection.
-   **EPUB Export**: Generate high-quality, standard-compliant EPUB files directly on your device. Compatible with Moon+ Reader, Kindle, and other e-reader apps.
-   **Background Downloads**: Supports long-running background tasks to download hundreds of chapters while you use other apps or turn off your screen.
-   **Library Management**: Organize your collection, track reading progress, and manage updates for ongoing stories.
-   **Smart Updates**: Checks for new chapters and updates your archive efficiently.
-   **Privacy Focused**: All data lives on your device. No external servers or accounts required.

## 🛠 Tech Stack

This project leverages a modern mobile stack optimized for performance and local file handling:

-   **Framework**: [React Native](https://reactnative.dev/) (via [Expo SDK 52+](https://expo.dev/))
-   **Navigation**: [Expo Router](https://docs.expo.dev/router/introduction/) (File-based routing)
-   **UI Library**: [React Native Paper](https://callstack.github.io/react-native-paper/) (Material Design)
-   **Parsing Engine**: `cheerio` (Fast HTML parsing)
-   **Networking**: Native `fetch` API + `react-native-webview` (Headless mode for Cloudflare bypass)
-   **Storage**: `expo-file-system` (Content) & `@react-native-async-storage/async-storage` (Metadata)
-   **EPUB Engine**: Custom implementation using `jszip` and XML templating (Platform-agnostic)

## 📦 Prerequisites

Before running the project, ensure you have the following installed:

-   **Node.js** (LTS version recommended)
-   **npm** (comes with Node.js)
-   **Expo Go** app on your Android/iOS device (Available on Play Store/App Store).

## 💿 Installation

1.  Clone the repository:
    ```bash
    git clone https://github.com/vinicius741/Webnovel-Archiver-Android.git
    cd Webnovel-Archiver-Android
    ```

2.  Install dependencies:
    ```bash
    npm install
    ```

## 🏃‍♂️ How to Run

### on Development (Expo Go)

1.  Start the development server:
    ```bash
    npx expo start
    ```

2.  **Android**: Scan the QR code with the **Expo Go** app.
3.  **iOS**: Scan the QR code with the **Camera** app.

### Build Local APK

To generate an installable APK file directly on your machine (requires Android SDK):

```bash
npm install -g eas-cli
npx eas build -p android --profile preview --local
```

## 📖 Usage Guide

### 1. Adding a Story
-   Tap the **"+" (Add)** button on the home screen.
-   Paste the URL of the webnovel (e.g., a RoyalRoad fiction page).
-   Tap **"Add"**. The app will fetch metadata and add it to your library.

### 2. Downloading Content
-   Open a story from your library.
-   Tap **"Download All"** or **"Update"**.
-   The app will start downloading chapters in the background. You can monitor progress on the dashboard.

### 3. Reading & Exporting
-   Once downloaded, simple tap **"Read"** to open the generated EPUB if available.
-   To create/update the ebook file, tap **"Generate EPUB"**.
-   The EPUB file is saved to your device's Documents folder and can be opened with any standard e-reader.

## 📂 Project Structure

-   `src/app`: Screens and navigation logic (Expo Router).
-   `src/components`: Reusable UI components (StoryCard, ProgressBar, etc.).
-   `src/services`: Core business logic.
    -   `FetcherFactory.ts`: Handles URL parsing and fetcher selection.
    -   `EpubGenerator.ts`: Logic for constructing EPUB files.
-   `src/theme`: App theming and styling.
-   `documentation`: Detailed tech docs and decision logs.

## 🤝 Contributing

Contributions are welcome! Please read the `documentation/` folder to understand the architecture before submitting a PR.
