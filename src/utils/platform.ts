import { Platform } from "react-native";
import Constants from "expo-constants";

/**
 * Check if the app is running in Expo Go.
 * Expo Go has limitations and some native modules don't work.
 */
export const isExpoGo = (): boolean => {
  return String(Constants.executionEnvironment) === "storeClient";
};

/**
 * Check if running on Android with native module support.
 * Returns false on iOS or when running in Expo Go.
 */
export const isAndroidNative = (): boolean => {
  return Platform.OS === "android" && !isExpoGo();
};
