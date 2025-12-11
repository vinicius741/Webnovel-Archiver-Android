import { useWindowDimensions } from 'react-native';

export const useScreenLayout = () => {
    const { width, height } = useWindowDimensions();

    // Define a breakpoint for "large" screens (e.g. tablet/foldable inner screen)
    // Increased to 700 to prevent large phones (e.g. Pro Max, or with 'Small' display size)
    // from triggering tablet layout.
    const isLargeScreen = width >= 700;

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
