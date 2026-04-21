import { renderHook } from "@testing-library/react-native";
import { useScreenLayout } from "../useScreenLayout";

jest.mock("react-native", () => ({
  useWindowDimensions: jest.fn(),
  Platform: { OS: "android" },
}));

jest.mock("expo-constants", () => ({
  __esModule: true,
  default: {
    executionEnvironment: "bare", // Default to not being in Expo Go
  },
  ExecutionEnvironment: {
    StoreClient: "storeClient",
    Standalone: "standalone",
    Bare: "bare",
  },
}));

jest.mock("@logicwind/react-native-fold-detection", () => ({
  useFoldingFeature: jest.fn(),
}));

describe("useScreenLayout", () => {
  const mockUseWindowDimensions = require("react-native").useWindowDimensions;
  const mockUseFoldingFeature = require("@logicwind/react-native-fold-detection").useFoldingFeature;
  const mockConstants = require("expo-constants").default;

  beforeEach(() => {
    jest.clearAllMocks();
    // Default to no folding features
    mockUseFoldingFeature.mockReturnValue({ layoutInfo: { displayFeatures: [] } });
    // Default to not running in Expo Go
    mockConstants.executionEnvironment = "bare";
  });

  describe("phone devices (1 column)", () => {
    it("should detect small phone", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 375, height: 812 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
      expect(result.current.shortestSide).toBe(375);
    });

    it("should detect iPhone Pro Max (430dp) as phone", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 430, height: 932 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
      expect(result.current.shortestSide).toBe(430);
    });

    it("should detect Samsung Galaxy S Ultra (412dp) as phone", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 412, height: 915 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });

    it("should stay 1 column in landscape rotation", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 915, height: 412 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
      expect(result.current.shortestSide).toBe(412);
    });

    it("should detect Samsung Galaxy S25 (393dp) as phone", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 393, height: 873 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });

    it("should handle very small screen", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 320, height: 568 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });
  });

  describe("Samsung Galaxy Z Fold7", () => {
    it("should show 1 column on cover screen (portrait) - no fold features", () => {
      // Typically ~411dp
      mockUseWindowDimensions.mockReturnValue({ width: 411, height: 960 });
      mockUseFoldingFeature.mockReturnValue({ layoutInfo: { displayFeatures: [] } });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });

    it("should show 1 column on cover screen even with high scaling (e.g. 710dp) due to aspect ratio", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 710, height: 1600 });
      mockUseFoldingFeature.mockReturnValue({ layoutInfo: { displayFeatures: [] } });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });

    it("should show 2 columns on inner screen (portrait) if folding feature detected", () => {
      // Inner screen is often ~750x832
      mockUseWindowDimensions.mockReturnValue({ width: 750, height: 832 });
      mockUseFoldingFeature.mockReturnValue({
        layoutInfo: { displayFeatures: [{ type: "fold", bounds: {} }] },
      });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.numColumns).toBe(2);
    });

    it("should show 2 columns on inner screen even without folding features if it meets threshold and squareness", () => {
      // width 750 (>= 720) and aspect ratio 1.1 (< 1.5)
      mockUseWindowDimensions.mockReturnValue({ width: 750, height: 832 });
      mockUseFoldingFeature.mockReturnValue({ layoutInfo: { displayFeatures: [] } });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.numColumns).toBe(2);
    });
  });

  describe("foldable devices", () => {
    it("should show 1 column on Galaxy Z Fold5 cover screen", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 384, height: 854 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });

    it("should show 2 columns on Galaxy Z Fold5 inner screen", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 682, height: 860 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.isLargeScreen).toBe(true);
      expect(result.current.numColumns).toBe(2);
    });

    it("should show 1 column on Galaxy Z Flip5 inner screen (phone-sized)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 360, height: 780 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });
  });

  describe("tablet devices (2-3 columns)", () => {
    it("should detect tablet (2 columns in portrait)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 768, height: 1024 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.isLargeScreen).toBe(true);
      expect(result.current.numColumns).toBe(2);
    });

    it("should detect iPad Mini (744dp shortest side)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 744, height: 1133 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.isLargeScreen).toBe(true);
      expect(result.current.numColumns).toBe(2);
    });

    it("should detect very large tablet (3 columns)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 1024, height: 1366 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.isLargeScreen).toBe(true);
      expect(result.current.numColumns).toBe(3);
    });

    it("should handle edge case at 3-column breakpoint (width 841)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 841, height: 1000 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.numColumns).toBe(3);
    });

    it("should handle edge case just below 3-column breakpoint (width 839)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 839, height: 1000 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.numColumns).toBe(2);
    });

    it("should show 3 columns in landscape", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 1280, height: 800 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.isLargeScreen).toBe(true);
      expect(result.current.numColumns).toBe(3);
      expect(result.current.shortestSide).toBe(800);
    });

    it("should handle tablet at exact 600dp threshold", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 600, height: 960 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.numColumns).toBe(2);
      expect(result.current.shortestSide).toBe(600);
    });
  });

  describe("Expo Go compatibility", () => {
    beforeEach(() => {
      mockConstants.executionEnvironment = "storeClient";
    });

    it("should force 1 column on a foldable cover screen in Expo Go", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 411, height: 960 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });

    it("should force 1 column on a tablet-sized window in Expo Go", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 768, height: 1024 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });

    it("should force 1 column even when the native fold signal says unfolded (Expo Go stub would never send this, but be defensive)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 750, height: 832 });
      mockUseFoldingFeature.mockReturnValue({
        layoutInfo: { displayFeatures: [{ type: "fold", bounds: {} }] },
      });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.numColumns).toBe(1);
    });
  });

  describe("edge cases", () => {
    it("should handle zero dimensions gracefully", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 0, height: 0 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
      expect(result.current.screenWidth).toBe(0);
      expect(result.current.screenHeight).toBe(0);
    });

    it("should handle device just below tablet threshold (599dp)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 599, height: 900 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(false);
      expect(result.current.isLargeScreen).toBe(false);
      expect(result.current.numColumns).toBe(1);
    });

    it("should handle device just at tablet threshold (600dp)", () => {
      mockUseWindowDimensions.mockReturnValue({ width: 600, height: 960 });

      const { result } = renderHook(() => useScreenLayout());

      expect(result.current.isTablet).toBe(true);
      expect(result.current.isLargeScreen).toBe(true);
      expect(result.current.numColumns).toBe(2);
    });

    it("should return correct values for various phone sizes", () => {
      const phones = [
        { w: 375, h: 812 },
        { w: 390, h: 844 },
        { w: 414, h: 896 },
        { w: 412, h: 915 },
        { w: 430, h: 932 },
      ];

      phones.forEach(({ w, h }) => {
        mockUseWindowDimensions.mockReturnValue({ width: w, height: h });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isTablet).toBe(false);
        expect(result.current.numColumns).toBe(1);
      });
    });

    it("should return correct values for various tablet sizes", () => {
      const tablets = [
        { w: 744, h: 1133 },
        { w: 800, h: 1280 },
        { w: 834, h: 1194 },
        { w: 1024, h: 1366 },
      ];

      tablets.forEach(({ w, h }) => {
        mockUseWindowDimensions.mockReturnValue({ width: w, height: h });

        const { result } = renderHook(() => useScreenLayout());

        expect(result.current.isTablet).toBe(true);
        expect(result.current.numColumns).toBeGreaterThanOrEqual(2);
      });
    });
  });
});