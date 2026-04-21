import { useWindowDimensions } from "react-native";
import { useSafeFoldingFeature } from "../utils/foldableUtils";
import { isExpoGo } from "../utils/platform";

export const useScreenLayout = () => {
  const { width, height } = useWindowDimensions();
  const { layoutInfo } = useSafeFoldingFeature();

  const shortestSide = Math.min(width, height);
  const longestSide = Math.max(width, height);
  const aspectRatio =
    shortestSide > 0 ? longestSide / shortestSide : 1;

  // Default to 1 column (Phone mode)
  let isTablet = false;

  // Detect if there is an active folding feature (hinge/fold) on the current screen.
  // This native signal is only available in dev/prod builds, never in Expo Go.
  const hasFoldingFeature =
    !!layoutInfo?.displayFeatures && layoutInfo.displayFeatures.length > 0;

  if (hasFoldingFeature) {
    // If we detect a physical hinge/fold, it's definitely the inner screen (unfolded)
    isTablet = true;
  } else if (shortestSide >= 600 && aspectRatio < 1.7) {
    // Without a hinge signal, we must be conservative: only treat as a tablet/unfolded
    // screen when the SHORTEST side is at least 600dp AND the aspect ratio is "square-ish".
    // This intentionally keeps narrow phones (ratio > 1.8) and Samsung Fold cover screens
    // (ratio ~2.2) at 1 column, which fixes the broken layout on the Z Fold 7 cover screen.
    //
    // NOTE: landscape phones that previously qualified under width >= 440 are now
    // intentionally excluded. A phone in landscape (e.g. 932×430, ratio ~2.2) will
    // correctly get numColumns=1 instead of a stretched 3-column tablet layout.
    isTablet = true;
  }

  // Expo Go does not include the native fold-detection module, so the hinge signal is
  // never available there. To avoid rendering an unusable 2-column layout when testing
  // in Expo Go on a foldable (e.g. Samsung Z Fold 7 cover screen), always fall back
  // to a single column. Multi-column foldable behavior still works in a dev/prod build.
  const forceSingleColumn = isExpoGo();

  const numColumns = forceSingleColumn
    ? 1
    : isTablet
      ? width >= 840
        ? 3
        : 2
      : 1;

  const isLargeScreen = numColumns > 1;

  return {
    isTablet: isTablet && !forceSingleColumn,
    isLargeScreen,
    numColumns,
    screenWidth: width,
    screenHeight: height,
    shortestSide,
    hasFoldingFeature,
  };
};