import { renderHook } from "@testing-library/react-native";
import { useScreenLayout } from "../useScreenLayout";

jest.mock("react-native", () => ({
  useWindowDimensions: jest.fn(),
}));

const mockUseFoldLayoutMode = jest.fn();
const mockUseSafeFoldingFeature = jest.fn();

jest.mock("../../context/FoldLayoutContext", () => ({
  useFoldLayoutMode: () => mockUseFoldLayoutMode(),
}));

jest.mock("../../utils/foldableUtils", () => ({
  useSafeFoldingFeature: () => mockUseSafeFoldingFeature(),
}));

describe("useScreenLayout", () => {
  const mockUseWindowDimensions = require("react-native").useWindowDimensions as jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    mockUseWindowDimensions.mockReturnValue({ width: 375, height: 812 });
    mockUseFoldLayoutMode.mockReturnValue({
      foldLayoutMode: "auto",
    });
    mockUseSafeFoldingFeature.mockReturnValue({
      layoutInfo: { displayFeatures: [] },
    });
  });

  it("treats a Fold cover-like Expo Go window as compact in auto mode", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 411, height: 960 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("compact");
    expect(result.current.heightClass).toBe("expanded");
    expect(result.current.numColumns).toBe(1);
    expect(result.current.isLargeScreen).toBe(false);
    expect(result.current.isTwoPane).toBe(false);
    expect(result.current.isCompactHeight).toBe(false);
  });

  it("promotes a square-ish inner Fold window below 600dp to medium", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 560, height: 760 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("medium");
    expect(result.current.heightClass).toBe("medium");
    expect(result.current.numColumns).toBe(2);
    expect(result.current.isLargeScreen).toBe(true);
    expect(result.current.isTwoPane).toBe(true);
  });

  it("promotes a narrower inner Fold-like window to medium", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 480, height: 760 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("medium");
    expect(result.current.heightClass).toBe("medium");
    expect(result.current.numColumns).toBe(2);
    expect(result.current.isTwoPane).toBe(true);
  });

  it("classifies an unfolded Fold portrait window as medium", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 768, height: 832 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("medium");
    expect(result.current.heightClass).toBe("medium");
    expect(result.current.numColumns).toBe(2);
    expect(result.current.isLargeScreen).toBe(true);
    expect(result.current.isTwoPane).toBe(true);
  });

  it("classifies an unfolded Fold landscape window as expanded", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 968, height: 720 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("expanded");
    expect(result.current.heightClass).toBe("medium");
    expect(result.current.numColumns).toBe(3);
    expect(result.current.isLargeScreen).toBe(true);
    expect(result.current.isTwoPane).toBe(true);
  });

  it("drops medium-width windows to one column when height is compact", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 700, height: 420 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("medium");
    expect(result.current.heightClass).toBe("compact");
    expect(result.current.isCompactHeight).toBe(true);
    expect(result.current.numColumns).toBe(1);
    expect(result.current.isTwoPane).toBe(false);
  });

  it("keeps split-screen medium windows in two columns when height is sufficient", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 700, height: 900 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("medium");
    expect(result.current.heightClass).toBe("expanded");
    expect(result.current.numColumns).toBe(2);
    expect(result.current.isTwoPane).toBe(true);
  });

  it("uses the same layout behavior regardless of Expo environment", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 768, height: 1024 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("medium");
    expect(result.current.numColumns).toBe(2);
    expect(result.current.isTwoPane).toBe(true);
  });

  it("updates from folded to unfolded dimensions on rerender", () => {
    let dimensions = { width: 390, height: 844 };
    mockUseWindowDimensions.mockImplementation(() => dimensions);

    const { result, rerender } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("compact");
    expect(result.current.numColumns).toBe(1);

    dimensions = { width: 768, height: 832 };
    rerender({});

    expect(result.current.widthClass).toBe("medium");
    expect(result.current.heightClass).toBe("medium");
    expect(result.current.numColumns).toBe(2);
    expect(result.current.isTwoPane).toBe(true);
  });

  it("handles zero dimensions gracefully", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 0, height: 0 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("compact");
    expect(result.current.heightClass).toBe("compact");
    expect(result.current.numColumns).toBe(1);
    expect(result.current.screenWidth).toBe(0);
    expect(result.current.screenHeight).toBe(0);
  });

  it("respects the 600dp and 840dp width thresholds", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 399, height: 1100 });
    const compact = renderHook(() => useScreenLayout());
    expect(compact.result.current.widthClass).toBe("compact");
    expect(compact.result.current.numColumns).toBe(1);

    mockUseWindowDimensions.mockReturnValue({ width: 600, height: 900 });
    const medium = renderHook(() => useScreenLayout());
    expect(medium.result.current.widthClass).toBe("medium");
    expect(medium.result.current.numColumns).toBe(2);

    mockUseWindowDimensions.mockReturnValue({ width: 840, height: 900 });
    const expanded = renderHook(() => useScreenLayout());
    expect(expanded.result.current.widthClass).toBe("expanded");
    expect(expanded.result.current.numColumns).toBe(3);
  });

  it("does not promote tall narrow cover-like windows below 600dp", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 390, height: 844 });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("compact");
    expect(result.current.numColumns).toBe(1);
    expect(result.current.isTwoPane).toBe(false);
  });

  it("forces cover mode to compact", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 768, height: 1024 });
    mockUseFoldLayoutMode.mockReturnValue({
      foldLayoutMode: "cover",
    });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("compact");
    expect(result.current.numColumns).toBe(1);
    expect(result.current.isTwoPane).toBe(false);
  });

  it("forces inner mode to medium on the Expo Go fold window", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 411, height: 960 });
    mockUseFoldLayoutMode.mockReturnValue({
      foldLayoutMode: "inner",
    });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.widthClass).toBe("medium");
    expect(result.current.numColumns).toBe(2);
    expect(result.current.isTwoPane).toBe(true);
  });

  it("uses native fold detection in auto mode for Android builds", () => {
    mockUseWindowDimensions.mockReturnValue({ width: 540, height: 760 });
    mockUseSafeFoldingFeature.mockReturnValue({
      layoutInfo: { displayFeatures: [{ type: "fold" }] },
    });

    const { result } = renderHook(() => useScreenLayout());

    expect(result.current.hasFoldingFeature).toBe(true);
    expect(result.current.widthClass).toBe("medium");
    expect(result.current.numColumns).toBe(2);
    expect(result.current.isTwoPane).toBe(true);
  });
});
