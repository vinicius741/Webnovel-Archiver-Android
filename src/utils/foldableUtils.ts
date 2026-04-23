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

const CAN_USE_NATIVE_FOLD = isAndroidNative();

const useFoldingFeatureFallback = (): SafeFoldingFeature => DEFAULT_FOLDING_STATE;

const useNativeFoldingFeature: (() => SafeFoldingFeature) | null = CAN_USE_NATIVE_FOLD
  ? (() => {
      const { useFoldingFeature } = require("@logicwind/react-native-fold-detection");
      return () => useFoldingFeature() as unknown as SafeFoldingFeature;
    })()
  : null;

export const useSafeFoldingFeature: () => SafeFoldingFeature = CAN_USE_NATIVE_FOLD && useNativeFoldingFeature
  ? useNativeFoldingFeature
  : useFoldingFeatureFallback;
