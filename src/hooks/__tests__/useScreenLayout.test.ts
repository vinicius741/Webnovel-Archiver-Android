
import { renderHook } from '@testing-library/react-native';
import { useScreenLayout } from '../useScreenLayout';

jest.mock('react-native', () => ({
    useWindowDimensions: jest.fn(),
}));

describe('useScreenLayout', () => {
    const mockUseWindowDimensions = require('react-native').useWindowDimensions;

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should detect small screen (phone)', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 390, height: 844 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(false);
        expect(result.current.numColumns).toBe(1);
        expect(result.current.screenWidth).toBe(390);
        expect(result.current.screenHeight).toBe(844);
    });

    it('should detect large screen with 2 columns (tablet/foldable)', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 768, height: 1024 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(true);
        expect(result.current.numColumns).toBe(2);
        expect(result.current.screenWidth).toBe(768);
        expect(result.current.screenHeight).toBe(1024);
    });

    it('should detect very large screen with 3 columns', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 1024, height: 1366 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(true);
        expect(result.current.numColumns).toBe(3);
        expect(result.current.screenWidth).toBe(1024);
        expect(result.current.screenHeight).toBe(1366);
    });

    it('should handle edge case at breakpoint (width 700)', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 700, height: 900 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(true);
        expect(result.current.numColumns).toBe(2);
    });

    it('should handle edge case just below breakpoint (width 699)', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 699, height: 900 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(false);
        expect(result.current.numColumns).toBe(1);
    });

    it('should handle edge case at 3-column breakpoint (width 841)', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 841, height: 1000 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(true);
        expect(result.current.numColumns).toBe(3);
    });

    it('should handle edge case just below 3-column breakpoint (width 839)', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 839, height: 1000 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(true);
        expect(result.current.numColumns).toBe(2);
    });

    it('should handle very small screen', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 320, height: 568 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(false);
        expect(result.current.numColumns).toBe(1);
        expect(result.current.screenWidth).toBe(320);
        expect(result.current.screenHeight).toBe(568);
    });

    it('should handle large phone (Pro Max) correctly as small screen', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 430, height: 932 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(false);
        expect(result.current.numColumns).toBe(1);
    });

    it('should handle landscape orientation', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 932, height: 430 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(true);
        expect(result.current.numColumns).toBe(3);
    });

    it('should handle tablet in portrait orientation', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 810, height: 1080 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(true);
        expect(result.current.numColumns).toBe(2);
    });

    it('should return correct dimensions for various screen sizes', () => {
        const testCases = [
            { width: 375, height: 812, expectedColumns: 1, expectedLarge: false },
            { width: 414, height: 896, expectedColumns: 1, expectedLarge: false },
            { width: 744, height: 1133, expectedColumns: 2, expectedLarge: true },
            { width: 834, height: 1194, expectedColumns: 2, expectedLarge: true },
            { width: 1280, height: 800, expectedColumns: 3, expectedLarge: true },
        ];

        testCases.forEach(({ width, height, expectedColumns, expectedLarge }) => {
            mockUseWindowDimensions.mockReturnValue({ width, height });

            const { result } = renderHook(() => useScreenLayout());

            expect(result.current.screenWidth).toBe(width);
            expect(result.current.screenHeight).toBe(height);
            expect(result.current.numColumns).toBe(expectedColumns);
            expect(result.current.isLargeScreen).toBe(expectedLarge);
        });
    });

    it('should handle zero width and height gracefully', () => {
        mockUseWindowDimensions.mockReturnValue({ width: 0, height: 0 });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isLargeScreen).toBe(false);
        expect(result.current.numColumns).toBe(1);
        expect(result.current.screenWidth).toBe(0);
        expect(result.current.screenHeight).toBe(0);
    });
});
