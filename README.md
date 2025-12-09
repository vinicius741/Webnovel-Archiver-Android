# Webnovel Archiver (Android)

This is the Android mobile version of the Webnovel Archiver, built with React Native and Expo.

## Prerequisites

Before you begin, ensure you have the following installed on your computer:

- **Node.js** (LTS version recommended)
- **npm** (comes with Node.js)

### On your Phone

- Install the **Expo Go** app from the Google Play Store (Android) or App Store (iOS).
  - [Expo Go for Android](https://play.google.com/store/apps/details?id=host.exp.exponent)
  - [Expo Go for iOS](https://apps.apple.com/us/app/expo-go/id982107779)

## Installation

1. Open your terminal in the project directory.
2. Install the dependencies:

```bash
npm install
```

## How to Run on Your Phone

1. **Connect to the same Wi-Fi**: Ensure your computer and your phone are connected to the exact same Wi-Fi network.

2. **Start the Development Server**:
   Run the following command in your terminal:

```bash
npx expo start
```

3. **Open on Phone**:
   - **Android**: Open the **Expo Go** app on your phone. You should see "Recently in development" if you've logged in, or you can tap "Scan QR code" and scan the QR code displayed in your computer's terminal.
   - **iOS**: Open the standard **Camera** app on your iPhone and scan the QR code from the terminal. Tap the notification to open it in Expo Go.

4. **Wait for Bundle**: The app will build the JavaScript bundle and transfer it to your phone. Once confident, the app will launch.

## Troubleshooting

- **Network Issues**: If the app fails to connect even on the same Wi-Fi, you can try using a tunnel connection. Run:
  ```bash
  npx expo start --tunnel
  ```
  Note: This may be slower than a direct LAN connection.

- **Background Tasks**: This app uses background tasks for downloading. On some Android devices, aggressive battery optimization might kill the app. Ensure you allow the app to run in the background in your phone's settings if downloads are interrupted.
