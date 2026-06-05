import { useMemo } from "react";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useScreenLayout } from "../common/useScreenLayout";

type AdaptiveLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
  heightClass?: "compact" | "medium" | "expanded";
  isTwoPane?: boolean;
  isCompactHeight?: boolean;
};

/**
 * Computes responsive layout values for the library screen:
 * column count, item widths, and max content widths.
 */
export function useLibraryLayout() {
  const screenLayout = useScreenLayout() as AdaptiveLayout;
  const { numColumns, isLargeScreen, screenWidth } = screenLayout;
  const insets = useSafeAreaInsets();

  const layout = useMemo(() => {
    const GAP = 8;
    const totalPadding = 32;
    const safeAreaHorizontal = insets.left + insets.right;
    const maxContentWidth =
      numColumns === 1 ? 760 : numColumns === 2 ? 1040 : 1320;
    const effectiveWidth = Math.min(
      screenWidth - safeAreaHorizontal,
      maxContentWidth,
    );
    const availableWidth =
      effectiveWidth - totalPadding - (numColumns - 1) * GAP;
    const itemWidth = availableWidth / numColumns;

    return { maxContentWidth, itemWidth };
  }, [numColumns, screenWidth, insets.left, insets.right]);

  return {
    numColumns,
    isLargeScreen,
    screenWidth,
    maxContentWidth: layout.maxContentWidth,
    itemWidth: layout.itemWidth,
  };
}
