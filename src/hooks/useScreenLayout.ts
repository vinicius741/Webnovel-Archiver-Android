import { useWindowDimensions } from "react-native";

export const useScreenLayout = () => {
  const { width, height } = useWindowDimensions();

  // Define a breakpoint for "large" screens (e.g. tablet/foldable inner screen)
  // 440dp is above the widest phones (~430dp iPhone Pro Max) while catching
  // foldable inner screens even with aggressive display scaling (~453dp at 4x).
  const isLargeScreen = width >= 440;

  // Determine number of columns for grid layouts
  // 1 for phone (vertical), 2 or 3 for tablet/wide
  const numColumns = isLargeScreen ? (width > 840 ? 3 : 2) : 1;

  return {
    isLargeScreen,
    numColumns,
    screenWidth: width,
    screenHeight: height,
  };
};
