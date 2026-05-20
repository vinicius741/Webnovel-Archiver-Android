import React from "react";
import { render, fireEvent, act } from "@testing-library/react-native";
import SourceBrowserScreen from "../../../app/browser";
import { storageService } from "../../services/StorageService";
import { useTabs } from "../../hooks/useTabs";
import { prepareStorySyncData, buildStoryForAdd } from "../../services/story/storySyncOrchestrator";

// Mock dependencies
jest.mock("react-native-webview", () => {
  const { View } = require("react-native");
  return {
    WebView: jest.fn().mockImplementation((props) => <View {...props} testID="mock-webview" />),
  };
});

jest.mock("react-native-safe-area-context", () => {
  const React = require("react");
  const { View } = require("react-native");
  return {
    SafeAreaProvider: ({ children }: any) => React.createElement(View, null, children),
    SafeAreaView: ({ children, style, testID }: any) =>
      React.createElement(View, { style, testID: testID || "safe-area-view" }, children),
    useSafeAreaInsets: () => ({ top: 20, bottom: 20, left: 0, right: 0 }),
  };
});

jest.mock("react-native-paper", () => {
  const React = require("react");
  const { View, Text, TouchableOpacity } = jest.requireActual("react-native");

  const mockTheme = {
    colors: {
      primary: "#6200ee",
      onPrimary: "#ffffff",
      surfaceVariant: "#f0f0f0",
      onSurface: "#000000",
      onSurfaceVariant: "#666666",
      background: "#ffffff",
      primaryContainer: "#e0e0e0",
      elevation: {
        level1: "#ffffff",
        level2: "#ffffff",
      },
    },
    fonts: {
      titleLarge: { fontFamily: "sans-serif" },
    },
  };

  const MockPortal = ({ children }: any) => <>{children}</>;

  const MockDialog = ({ children, visible }: any) => {
    if (!visible) return null;
    return <View testID="dialog-container">{children}</View>;
  };
  MockDialog.Title = ({ children }: any) => <Text style={{ fontWeight: "bold" }}>{children}</Text>;
  MockDialog.Content = ({ children }: any) => <View>{children}</View>;
  MockDialog.Actions = ({ children }: any) => <View style={{ flexDirection: "row" }}>{children}</View>;

  const MockFAB = ({ label, onPress, testID, icon }: any) => (
    <TouchableOpacity onPress={onPress} testID={testID || "fab"}>
      <Text>{label || icon}</Text>
    </TouchableOpacity>
  );

  const MockIconButton = ({ icon, onPress, testID }: any) => (
    <TouchableOpacity onPress={onPress} testID={testID || `icon-button-${icon}`}>
      <Text>{icon}</Text>
    </TouchableOpacity>
  );

  const MockButton = ({ children, onPress, testID }: any) => (
    <TouchableOpacity onPress={onPress} testID={testID || "button"}>
      <Text>{children}</Text>
    </TouchableOpacity>
  );

  const MockRadioButton = ({ value, status }: any) => (
    <View testID={`radio-${value}`}>
      <Text>{status === "checked" ? "[X]" : "[ ]"}</Text>
    </View>
  );

  const MockCardContent = ({ children }: any) => <View>{children}</View>;

  const MockCard = ({ children, onPress, testID }: any) => (
    <TouchableOpacity onPress={onPress} testID={testID || "card"}>
      {children}
    </TouchableOpacity>
  );
  MockCard.Content = MockCardContent;

  return {
    useTheme: () => mockTheme,
    Portal: MockPortal,
    Dialog: MockDialog,
    FAB: MockFAB,
    IconButton: MockIconButton,
    RadioButton: MockRadioButton,
    Button: MockButton,
    Card: MockCard,
    Divider: () => <View style={{ height: 1, backgroundColor: "#ccc" }} />,
    Text: ({ children, style }: any) => <Text style={style}>{children}</Text>,
  };
});

jest.mock("../../theme/useAppTheme", () => ({
  useAppTheme: jest.fn().mockReturnValue({
    shapes: { dialogRadius: 20, fabRadius: 20, cardRadius: 15, buttonRadius: 10, chipRadius: 10, searchBarRadius: 10, elevationStyle: "flat" },
    buttonDefaults: { mode: "outlined", textTransform: "uppercase", borderWidth: 1, buttonHeight: 45 },
    colors: {
      primary: "#123456",
      onPrimary: "#abcdef",
      surface: "#fefefe",
      surfaceVariant: "#e0e0e0",
    },
  }),
}));

const mockBrowserRouter = {
  back: jest.fn(),
  push: jest.fn(),
  replace: jest.fn(),
};

const mockBrowserSearchParams = { url: undefined };

jest.mock("expo-router", () => ({
  useRouter: () => mockBrowserRouter,
  useLocalSearchParams: () => mockBrowserSearchParams,
}));

const mockShowAlert = jest.fn();
jest.mock("../../context/AlertContext", () => ({
  useAppAlert: () => ({ showAlert: mockShowAlert }),
}));

jest.mock("../../hooks/useTabs", () => ({
  useTabs: jest.fn().mockReturnValue({
    tabs: [
      { id: "tab1", name: "Reading" },
      { id: "tab2", name: "Completed" },
    ],
    hasCustomTabs: true,
  }),
}));

jest.mock("../../services/StorageService", () => ({
  storageService: {
    getStory: jest.fn(),
    addStory: jest.fn(),
  },
}));

jest.mock("../../services/story/storySyncOrchestrator", () => ({
  prepareStorySyncData: jest.fn(),
  buildStoryForAdd: jest.fn(),
}));

describe("SourceBrowserScreen", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockBrowserSearchParams.url = undefined;
  });

  it("should render landing page initially when no URL is provided", () => {
    const { getByText, queryByTestId } = render(<SourceBrowserScreen />);

    expect(getByText("Source Browser")).toBeTruthy();
    expect(getByText("Royal Road")).toBeTruthy();
    expect(getByText("Scribble Hub")).toBeTruthy();
    expect(queryByTestId("mock-webview")).toBeNull();
  });

  it("should load WebView when a source card is pressed", () => {
    const { getByText, getByTestId } = render(<SourceBrowserScreen />);

    const rrCard = getByText("Royal Road");
    fireEvent.press(rrCard);

    const webView = getByTestId("mock-webview");
    expect(webView).toBeTruthy();
    expect(webView.props.source.uri).toBe("https://www.royalroad.com");
  });

  it("should initialize WebView when url search param is provided", () => {
    mockBrowserSearchParams.url = "https://www.scribblehub.com" as any;
    const { getByTestId } = render(<SourceBrowserScreen />);

    const webView = getByTestId("mock-webview");
    expect(webView).toBeTruthy();
    expect(webView.props.source.uri).toBe("https://www.scribblehub.com");
  });

  it("should show download FAB when novel detail page is detected", async () => {
    mockBrowserSearchParams.url = "https://www.royalroad.com/fiction/12345/my-story" as any;
    const { getByTestId, getByText } = render(<SourceBrowserScreen />);

    const webView = getByTestId("mock-webview");
    
    // Simulate navigation to novel details page
    act(() => {
      webView.props.onNavigationStateChange({
        url: "https://www.royalroad.com/fiction/12345/my-story",
        loading: false,
        canGoBack: true,
        canGoForward: false,
        title: "My Story - Royal Road",
      });
    });

    const downloadFab = getByText("Download Story");
    expect(downloadFab).toBeTruthy();
  });

  it("should NOT show download FAB on non-story pages or chapter pages", () => {
    mockBrowserSearchParams.url = "https://www.royalroad.com" as any;
    const { getByTestId, queryByText } = render(<SourceBrowserScreen />);

    const webView = getByTestId("mock-webview");
    
    // Simulate navigation to home page
    act(() => {
      webView.props.onNavigationStateChange({
        url: "https://www.royalroad.com",
        loading: false,
        canGoBack: false,
        canGoForward: false,
        title: "Royal Road",
      });
    });

    expect(queryByText("Download Story")).toBeNull();

    // Simulate navigation to chapter page
    act(() => {
      webView.props.onNavigationStateChange({
        url: "https://www.royalroad.com/fiction/12345/my-story/chapter/6789/chap-1",
        loading: false,
        canGoBack: true,
        canGoForward: false,
        title: "Chapter 1",
      });
    });

    expect(queryByText("Download Story")).toBeNull();
  });

  it("should show import dialog and handle success", async () => {
    mockBrowserSearchParams.url = "https://www.scribblehub.com/series/12345/my-series" as any;
    const { getByTestId, getByText, getAllByText } = render(<SourceBrowserScreen />);

    const webView = getByTestId("mock-webview");
    
    // Navigate to novel page
    act(() => {
      webView.props.onNavigationStateChange({
        url: "https://www.scribblehub.com/series/12345/my-series",
        loading: false,
        canGoBack: true,
        canGoForward: false,
        title: "My Series - Scribble Hub",
      });
    });

    const downloadFab = getByText("Download Story");
    fireEvent.press(downloadFab);

    // Dialog should show up
    expect(getAllByText("Download Story").length).toBe(2);
    expect(getByText("Would you like to import this webnovel into your library?")).toBeTruthy();

    // Mock orchestrator
    const mockPrepared = {
      metadata: { title: "My Series" },
    };
    const mockStory = {
      id: "sh_12345",
      title: "My Series",
    };
    (prepareStorySyncData as jest.Mock).mockResolvedValue(mockPrepared);
    (buildStoryForAdd as jest.Mock).mockReturnValue(mockStory);
    (storageService.addStory as jest.Mock).mockResolvedValue(undefined);

    // Select a tab and press Import
    const importButton = getByText("Import");
    await act(async () => {
      fireEvent.press(importButton);
    });

    expect(prepareStorySyncData).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceUrl: "https://www.scribblehub.com/series/12345/my-series",
      })
    );
    expect(buildStoryForAdd).toHaveBeenCalledWith(mockPrepared, undefined);
    expect(storageService.addStory).toHaveBeenCalledWith(mockStory);
    expect(mockShowAlert).toHaveBeenCalledWith(
      "Success",
      'Added "My Series" to library.',
      expect.any(Array)
    );
  });
});
