### Project: Webnovel Archiver (Mobile Port)

### 1\. Overview

This document outlines the technical architecture and technology stack decisions for the Android mobile version of the Webnovel Archiver. The primary goal is to port the functionality of the original Python script (scraping, parsing, EPUB generation) to a **local-only** mobile environment.

**Core Philosophy:**

  * **Local-First:** No external backend servers. All processing (scraping, packaging) happens on the user's device.
  * **Developer Experience:** Leverage the developer's existing ReactJS expertise.
  * **Performance:** Efficient handling of file I/O and memory to prevent UI freezing on Android.

-----

### 2\. The Tech Stack

#### 2.1 Core Framework

  * **Decision:** **React Native (via Expo SDK 50+)**
  * **Rationale:**
      * Allows 90% code reuse of ReactJS patterns.
      * Expo's ecosystem (specifically `expo-file-system`) has matured enough to handle complex local file operations without needing to "eject" to bare native code.
      * Excellent support for OTA (Over-the-Air) updates and easy Android build generation.

#### 2.2 Navigation & Routing

  * **Decision:** **Expo Router**
  * **Rationale:**
      * Provides a file-based routing system similar to Next.js.
      * Simplifies deep linking and navigation state management.
      * Native look-and-feel transitions on Android.

#### 2.3 Networking & Scraping Engine

  * **Primary Tool:** **Native `fetch` API**
      * **Usage:** For standard HTTP requests to fetch chapter HTML.
      * **Configuration:** Must implement custom `User-Agent` headers to mimic a standard Android Chrome browser to avoid basic bot detection.
  * **Fallback Tool:** **`react-native-webview` (Headless Mode)**
      * **Usage:** Used only when `fetch` fails due to **Cloudflare** or complex JavaScript challenges. The WebView loads the page invisibly, executes the JS challenge, and extracts the HTML.
  * **HTML Parsing:** **`cheerio`**
      * **Rationale:** Fast, lightweight jQuery-like syntax for traversing the DOM. Unlike JSDOM, it does not rely on Node.js-specific APIs, making it compatible with the Hermes JS engine.

#### 2.4 File Management & Storage

  * **File I/O:** **`expo-file-system`**
      * **Rationale:** Essential for saving raw HTML chapters and the final EPUB files to the device's local storage.
  * **Persistent Data:** **`@react-native-async-storage/async-storage`**
      * **Usage:** Storing lightweight metadata (list of tracked novels, last read chapter, settings).
      * *Note:* If the library grows beyond 100+ novels, we will migrate to `expo-sqlite`.

#### 2.5 EPUB Generation (The Core Challenge)

  * **Decision:** **`JSZip` + Custom XML Templating**
  * **Rationale:**
      * Standard Node.js EPUB libraries (`epub-gen`, `nodepub`) **do not work** in React Native because they depend on Node's `fs` (file system) module.
      * **Approach:** We will manually construct the EPUB structure (Mimetype, Container XML, OPF Manifest, NCX TOC) using template strings and zip them using `JSZip`.

#### 2.6 UI Component Library

  * **Decision:** **React Native Paper**
  * **Rationale:**
      * Follows Material Design guidelines, ensuring the app feels "native" on Android immediately.
      * High-performance components (Cards, AppBars, FABs) out of the box.

-----

### 3\. Architecture Diagram (Local-Only Flow)

```mermaid
graph TD
    User[User] -->|Input URL| UI[React Native UI]
    UI -->|Start Job| Logic[Scraper Logic]
    
    subgraph "Device Local Environment"
        Logic -->|1. Fetch HTML| Network{Network Layer}
        Network -->|Option A: Fetch| Web[Webnovel Site]
        Network -->|Option B: WebView| Web
        
        Web -->|Return HTML| Logic
        Logic -->|2. Parse (Cheerio)| Parser
        Parser -->|3. Save Temp HTML| FS[Expo File System]
        
        FS -->|4. Batch Process| Zipper[JSZip Engine]
        Zipper -->|5. Generate .epub| Output[Downloads Folder]
    end
    
    Output -->|Open File| AndroidSystem[Android OS]
```

-----

### 4\. Known Trade-offs & Risks

| Feature | Challenge | Mitigation Strategy |
| :--- | :--- | :--- |
| **Cloudflare Protection** | High. Many novel sites use "I am a human" checks. | Implement a "WebView Fallback" mode. If a 403/503 is detected, open a hidden WebView to pass the check. |
| **Performance** | JS is single-threaded. Parsing 500 chapters can freeze the UI. | Use **Batching**. Process chapters in chunks of 10-20, using `setTimeout` or `InteractionManager` to yield control back to the UI thread between chunks. |
| **Memory Limits** | Loading 50MB of text strings into RAM for zipping can crash the app. | Do not hold all chapters in a variable. Write them to temporary files immediately. Read streams are not fully supported in RN JS, so careful memory management is required. |
| **Background Execution** | Android may kill the process if the screen turns off during a long download. | Keep the screen active using `expo-keep-awake` during active downloads. |