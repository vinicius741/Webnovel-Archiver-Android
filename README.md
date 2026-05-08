# Webnovel Archiver (Android)

A local-first Android app for downloading, archiving, and reading webnovels offline. Built with **React Native** and **Expo**.

## Key Features

- **Multi-Source Support** — Extensible `SourceProvider` architecture (RoyalRoad, Scribble Hub; add more via `SourceRegistry`).
- **Offline Library** — Download chapters for reading without an internet connection.
- **Built-in Reader** — WebView-based reader with TTS, image viewing, and last-read position tracking.
- **Text-to-Speech** — Background playback, lock-screen media controls, configurable voice/rate/pitch.
- **EPUB Export** — Generate EPUB 2.0 files with volume splitting and configurable chapter ranges.
- **Background Downloads** — Concurrent engine with persistent queue, pause/resume/retry.
- **Text Cleanup** — Sentence removal and regex rules with scoped targets (download, TTS, or both).
- **Library Organization** — Custom tabs, multi-select, search, tag filtering, and sorting.
- **Smart Updates** — New-chapter detection with intelligent merge and stale-EPUB marking.
- **Backup & Restore** — JSON-based export/import with merge-on-import.
- **Theming** — System, Light, or Dark mode (Material Design 3).

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | React Native via Expo SDK 54 |
| Language | TypeScript (strict mode) |
| Navigation | Expo Router (file-based) |
| UI | React Native Paper (Material Design 3) |
| HTML Parsing | cheerio |
| Storage | expo-file-system + @react-native-async-storage/async-storage |
| EPUB | Custom engine using jszip |
| TTS | expo-speech + custom native Kotlin module for Android Media Session |
| Notifications | @notifee/react-native |

## Getting Started

### Prerequisites

- Node.js (LTS)
- Expo Go app (for development)

### Install

```bash
git clone https://github.com/vinicius741/Webnovel-Archiver-Android.git
cd Webnovel-Archiver-Android
npm install
```

### Development

```bash
npx expo start
```

Scan the QR code with **Expo Go** (Android) or the **Camera** app (iOS).

### Build APK

```bash
npm install -g eas-cli
npx eas build -p android --profile preview --local
```

## Development

```bash
npm run lint          # ESLint
npm run typecheck     # TypeScript validation
npm test              # Jest tests
npm run check         # lint + typecheck + coverage + quality
```

See `AGENTS.md` for the full list of scripts, testing instructions, and code quality gates.

## Project Structure

```
app/                  # Expo Router screens
src/
  components/         # UI components (details, reader, library, downloads, tabs)
  hooks/              # Custom React hooks
  services/           # Business logic (download, epub, source, storage, tts)
  types/              # TypeScript type definitions
  utils/              # Pure utility functions
  context/            # React contexts
  theme/              # Theme configurations
modules/              # Custom native modules (Kotlin)
plugins/              # Expo config plugins
documentation/        # Detailed tech docs and decision logs
```

TypeScript path aliases are configured for `src/*` and `app/*`.

## Contributing

Contributions are welcome! Please read `AGENTS.md` and the `documentation/` folder before submitting a PR.
