import { isAndroidNative } from "./platform";

export type SafeFoldingFeature = {
  isTableTop: boolean;
  isBook: boolean;
  isFlat: boolean;
  layoutInfo: {
    displayFeatures?: unknown[];
    [key: string]: unknown;
  };
};

const DEFAULT_FOLDING_STATE: SafeFoldingFeature = {
  isTableTop: false,
  isBook: false,
  isFlat: true,
  layoutInfo: { displayFeatures: [] },
};

// Determined once at module load. This is stable for the entire lifetime of the
// JS runtime, which is what allows us to safely branch on it at the hook's top
// level without violating the Rules of Hooks.
//
// We guard on isAndroidNative() instead of just !isExpoGo() because the native
// fold-detection module is Android-only. On iOS, FoldingFeatureProvider doesn't
// provide context, so useNativeFoldingFeature() throws during React rendering
// — and a try-catch around a hook call cannot intercept React render errors.
const CAN_USE_NATIVE_FOLD = isAndroidNative();

const useFoldingFeatureFallback = (): SafeFoldingFeature => DEFAULT_FOLDING_STATE;

// Conditionally imported only when the native module is available, to avoid
// crashes in environments where it is not linked (Expo Go, iOS, web).
const useNativeFoldingFeature: (() => SafeFoldingFeature) | null = CAN_USE_NATIVE_FOLD
  ? (() => {
      // require() is intentionally used here so the module is not evaluated in
      // environments where the native module is absent. The surrounding
      // CAN_USE_NATIVE_FOLD guard ensures this path only runs on Android
      // native builds.
      const { useFoldingFeature } = require("@logicwind/react-native-fold-detection");
      return () => useFoldingFeature() as unknown as SafeFoldingFeature;
    })()
  : null;

/**
 * A safe wrapper around the native folding feature hook.
 * Returns a default state on any platform where the native module is unavailable
 * (Expo Go, iOS, web, or if the module failed to load).
 *
 * Implementation detail: we pick ONE of two hook implementations at module load
 * based on whether the native fold-detection module is available. The chosen
 * hook is then used consistently for every render, preserving the Rules of Hooks.
 */
export const useSafeFoldingFeature: () => SafeFoldingFeature = CAN_USE_NATIVE_FOLD && useNativeFoldingFeature
  ? useNativeFoldingFeature
  : useFoldingFeatureFallback;
