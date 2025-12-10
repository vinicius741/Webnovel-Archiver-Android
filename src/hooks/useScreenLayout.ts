import { useWindowDimensions } from 'react-native';

export const useScreenLayout = () => {
    const { width, height } = useWindowDimensions();

    // Define a breakpoint for "large" screens (e.g. tablet/foldable inner screen)
    // Lowered to 500 to account for high pixel density on foldables
    const isLargeScreen = width >= 500;

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
