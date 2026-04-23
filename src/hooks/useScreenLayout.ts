import { useWindowDimensions } from "react-native";
import { useFoldLayoutMode } from "../context/FoldLayoutContext";

export type ScreenLayoutClass = "compact" | "medium" | "expanded";

const getAutomaticWidthClass = (
  width: number,
  shortestSide: number,
  aspectRatio: number,
): ScreenLayoutClass => {
  if (width >= 840) {
    return "expanded";
  }

  if (width >= 600) {
    return "medium";
  }

  // Some fold configurations can report an inner-screen window slightly below
  // 600dp wide. Promote these square-ish larger windows so the inner display
  // still gets the tablet-style layout.
  if (shortestSide >= 460 && aspectRatio <= 1.8) {
    return "medium";
  }

  return "compact";
};

const getHeightClass = (height: number): ScreenLayoutClass => {
  if (height >= 900) {
    return "expanded";
  }

  if (height >= 480) {
    return "medium";
  }

  return "compact";
};

export const useScreenLayout = () => {
  const { width, height } = useWindowDimensions();
  const { foldLayoutMode } = useFoldLayoutMode();
  const shortestSide = Math.min(width, height);
  const longestSide = Math.max(width, height);
  const aspectRatio = shortestSide > 0 ? longestSide / shortestSide : Number.POSITIVE_INFINITY;
  const automaticWidthClass = getAutomaticWidthClass(width, shortestSide, aspectRatio);
  const widthClass =
    foldLayoutMode === "cover"
      ? "compact"
      : foldLayoutMode === "inner"
        ? width >= 840
          ? "expanded"
          : "medium"
        : automaticWidthClass;
  const heightClass = getHeightClass(height);
  const isCompactHeight = heightClass === "compact";

  const numColumns =
    widthClass === "expanded"
      ? 3
      : widthClass === "medium"
        ? isCompactHeight
          ? 1
          : 2
        : 1;
  const isLargeScreen = numColumns > 1;
  const isTwoPane = widthClass !== "compact" && !isCompactHeight;

  return {
    widthClass,
    heightClass,
    numColumns,
    isLargeScreen,
    isTwoPane,
    isCompactHeight,
    screenWidth: width,
    screenHeight: height,
    foldLayoutMode,
  };
};
