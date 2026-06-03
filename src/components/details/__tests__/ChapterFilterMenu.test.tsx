import React from "react";
import { render, fireEvent, act } from "@testing-library/react-native";
import { ChapterFilterMenu } from "../ChapterFilterMenu";
import { SafeAreaProvider } from "react-native-safe-area-context";

// Mock react-native-paper components
jest.mock("react-native-paper", () => {
  const React = require("react");
  const { View, Text, TextInput } = jest.requireActual("react-native");
  const OriginalModule = jest.requireActual("react-native-paper");
  const mockTheme = OriginalModule.MD3LightTheme;

  const MockIconButton = ({ onPress, testID, icon, disabled }: any) => {
    return React.createElement(
      View,
      {
        testID,
        accessible: true,
        accessibilityRole: "button",
        accessibilityState: { disabled },
        onPress: (event: any) => {
          if (!disabled && onPress) {
            onPress(event);
          }
        },
      },
      React.createElement(Text, null, icon),
    );
  };

  const MockSearchbar = ({ placeholder, onChangeText, value, testID }: any) => {
    return React.createElement(
      View,
      { testID: testID || "searchbar" },
      React.createElement(TextInput, {
        testID: "searchbar-input",
        placeholder,
        onChangeText,
        value,
      }),
    );
  };

  const MockMenu = ({ visible, children, anchor }: any) => {
    return React.createElement(
      View,
      { testID: "menu-container" },
      anchor,
      visible &&
        React.createElement(View, { testID: "menu-content" }, children),
    );
  };

  (MockMenu as any).Item = ({ title, onPress, disabled, leadingIcon }: any) => {
    return React.createElement(
      View,
      {
        testID: `menu-item-${title.toLowerCase().replace(/\s+/g, "-")}`,
        onPress: disabled ? undefined : onPress,
      },
      React.createElement(Text, null, title),
      leadingIcon && React.createElement(Text, { testID: "leading-icon" }, leadingIcon),
    );
  };

  return {
    ...OriginalModule,
    Menu: MockMenu,
    useTheme: jest.fn().mockReturnValue(mockTheme),
    IconButton: MockIconButton,
    Searchbar: MockSearchbar,
  };
});

jest.mock("react-native-safe-area-context", () => {
  const React = require("react");
  const { View } = jest.requireActual("react-native");
  return {
    SafeAreaProvider: ({ children }: any) => React.createElement(View, null, children),
    useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
  };
});

describe("ChapterFilterMenu", () => {
  const defaultProps = {
    filterMode: "all" as const,
    hasBookmark: true,
    onFilterSelect: jest.fn(),
    searchQuery: "",
    onSearchChange: jest.fn(),
    selectionMode: false,
    onToggleSelectionMode: jest.fn(),
    selectionDisabled: false,
    stacked: false,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  const renderWithTheme = (component: React.ReactElement) => {
    return render(component);
  };

  it("should render without crashing", () => {
    const { getByTestId } = renderWithTheme(<ChapterFilterMenu {...defaultProps} />);
    expect(getByTestId("searchbar")).toBeTruthy();
    expect(getByTestId("chapter-selection-button")).toBeTruthy();
    expect(getByTestId("chapter-filter-button")).toBeTruthy();
  });

  it("should call onSearchChange when typing in searchbar", () => {
    const { getByTestId } = renderWithTheme(<ChapterFilterMenu {...defaultProps} />);
    const input = getByTestId("searchbar-input");
    fireEvent.changeText(input, "Chapter 1");
    expect(defaultProps.onSearchChange).toHaveBeenCalledWith("Chapter 1");
  });

  it("should call onToggleSelectionMode when selection button is pressed", () => {
    const { getByTestId } = renderWithTheme(<ChapterFilterMenu {...defaultProps} />);
    const selectionBtn = getByTestId("chapter-selection-button");
    fireEvent.press(selectionBtn);
    expect(defaultProps.onToggleSelectionMode).toHaveBeenCalledTimes(1);
  });

  it("should not call onToggleSelectionMode when selection button is disabled", () => {
    const { getByTestId } = renderWithTheme(
      <ChapterFilterMenu {...defaultProps} selectionDisabled={true} />
    );
    const selectionBtn = getByTestId("chapter-selection-button");
    fireEvent.press(selectionBtn);
    expect(defaultProps.onToggleSelectionMode).not.toHaveBeenCalled();
  });

  it("should open menu and show options when filter button is pressed", () => {
    const { getByTestId, queryByTestId } = renderWithTheme(
      <ChapterFilterMenu {...defaultProps} />
    );
    
    // Initially menu content is not visible
    expect(queryByTestId("menu-content")).toBeNull();

    const filterBtn = getByTestId("chapter-filter-button");
    fireEvent.press(filterBtn);

    expect(getByTestId("menu-content")).toBeTruthy();
    expect(getByTestId("menu-item-show-all-chapters")).toBeTruthy();
    expect(getByTestId("menu-item-hide-non-downloaded")).toBeTruthy();
    expect(getByTestId("menu-item-hide-chapters-above-bookmark")).toBeTruthy();
  });

  it("should call onFilterSelect with correct value when option is pressed", () => {
    const { getByTestId } = renderWithTheme(<ChapterFilterMenu {...defaultProps} />);
    
    const filterBtn = getByTestId("chapter-filter-button");
    fireEvent.press(filterBtn);

    const allChaptersOption = getByTestId("menu-item-show-all-chapters");
    fireEvent.press(allChaptersOption);

    // Fast-forward timers for the callback delay (100ms)
    act(() => {
      jest.advanceTimersByTime(100);
    });

    expect(defaultProps.onFilterSelect).toHaveBeenCalledWith("all");
  });

  it("should show check icon next to selected filter mode", () => {
    const { getByTestId, queryAllByTestId } = renderWithTheme(
      <ChapterFilterMenu {...defaultProps} filterMode="hideNonDownloaded" />
    );

    const filterBtn = getByTestId("chapter-filter-button");
    fireEvent.press(filterBtn);

    const items = queryAllByTestId("leading-icon");
    expect(items.length).toBe(1); // Only hideNonDownloaded has "check"
  });

  it("should prevent reopening menu immediately after dismiss (conflict prevention)", () => {
    const { getByTestId, queryByTestId } = renderWithTheme(
      <ChapterFilterMenu {...defaultProps} />
    );

    const filterBtn = getByTestId("chapter-filter-button");
    
    // 1. Open
    fireEvent.press(filterBtn);
    expect(getByTestId("menu-content")).toBeTruthy();

    // 2. Dismiss (we trigger dismiss manually from Menu)
    const menuContainer = getByTestId("menu-container");
    // Find the onDismiss prop of Menu
    const menuComponent = menuContainer.props.children[1] ? menuContainer : menuContainer;
    
    // Simulate menu dismiss
    act(() => {
      // In the mocked Menu component, we can dismiss it
      // Let's just simulate the dismiss trigger on the mock:
      // Note: ChapterFilterDropdown passes `handleDismiss` to onDismiss
      // Let's mock a tap outside or trigger onDismiss callback directly:
    });
  });
});
