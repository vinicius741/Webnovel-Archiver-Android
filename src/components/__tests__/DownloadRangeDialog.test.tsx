import React from "react";
import { render, fireEvent } from "@testing-library/react-native";
import { DownloadRangeDialog } from "../details/DownloadRangeDialog";
import { MD3LightTheme, PaperProvider } from "react-native-paper";

describe("DownloadRangeDialog", () => {
  const mockTheme = {
    ...MD3LightTheme,
    colors: {
      ...MD3LightTheme.colors,
      primary: "#6200ee",
    },
  };

  const defaultProps = {
    visible: true,
    onDismiss: jest.fn(),
    onDownload: jest.fn(),
    totalChapters: 100,
    hasBookmark: false,
  };

  const renderWithTheme = (component: React.ReactElement) => {
    return render(<PaperProvider theme={mockTheme}>{component}</PaperProvider>);
  };

  const switchMode = (container: ReturnType<typeof render>, mode: string) => {
    const buttons = container.getAllByRole("button");
    const target = buttons.find(
      (btn) =>
        btn.props.accessibilityState?.checked !== undefined &&
        btn.findAll(
          (node: any) =>
            node.props.children === mode ||
            (node.props.children &&
              typeof node.props.children === "object" &&
              Array.isArray(node.props.children) &&
              node.props.children.some(
                (c: any) =>
                  c.props &&
                  c.props.children &&
                  c.props.children.some(
                    (cc: any) => cc.props?.children === mode,
                  ),
              )),
        ).length > 0,
    );
    if (target) {
      fireEvent.press(target);
    }
  };

  beforeEach(() => {
    defaultProps.onDismiss.mockClear();
    defaultProps.onDownload.mockClear();
  });

  it("should render dialog when visible", () => {
    const { getByText } = renderWithTheme(
      <DownloadRangeDialog {...defaultProps} />,
    );

    expect(getByText("Download Range")).toBeTruthy();
  });

  it("should not render when not visible", () => {
    const { queryByText } = renderWithTheme(
      <DownloadRangeDialog {...defaultProps} visible={false} />,
    );

    expect(queryByText("Download Range")).toBeNull();
  });

  it("should display total chapters", () => {
    const { getByText } = renderWithTheme(
      <DownloadRangeDialog {...defaultProps} />,
    );

    expect(getByText("Total Chapters: 100")).toBeTruthy();
  });

  it("should render Cancel button", () => {
    const { getByText } = renderWithTheme(
      <DownloadRangeDialog {...defaultProps} />,
    );

    expect(getByText("Cancel")).toBeTruthy();
  });

  it("should render Download button", () => {
    const { getByText } = renderWithTheme(
      <DownloadRangeDialog {...defaultProps} />,
    );

    expect(getByText("Download")).toBeTruthy();
  });

  it("should call onDismiss when Cancel button is pressed", () => {
    const { getByText } = renderWithTheme(
      <DownloadRangeDialog {...defaultProps} />,
    );

    fireEvent.press(getByText("Cancel"));
    expect(defaultProps.onDismiss).toHaveBeenCalledTimes(1);
  });

  it("should render three mode buttons", () => {
    const { getByText } = renderWithTheme(
      <DownloadRangeDialog {...defaultProps} />,
    );

    expect(getByText("Range")).toBeTruthy();
    expect(getByText("Bookmark")).toBeTruthy();
    expect(getByText("Count")).toBeTruthy();
  });

  describe("Range mode", () => {
    it("should initialize start input to 1", () => {
      const { getByDisplayValue } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      expect(getByDisplayValue("1")).toBeTruthy();
    });

    it("should initialize end input to total chapters", () => {
      const { getByDisplayValue } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      expect(getByDisplayValue("100")).toBeTruthy();
    });

    it("should show error for non-numeric input", () => {
      const { getByText, getByDisplayValue, queryByText } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      const input = getByDisplayValue("1");
      fireEvent.changeText(input, "abc");

      fireEvent.press(getByText("Download"));
      expect(queryByText("Please enter valid numbers.")).toBeTruthy();
    });

    it("should show error for start chapter below 1", () => {
      const { getByText, getByDisplayValue, queryByText } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      const input = getByDisplayValue("1");
      fireEvent.changeText(input, "0");

      fireEvent.press(getByText("Download"));
      expect(
        queryByText("Range must be between 1 and 100."),
      ).toBeTruthy();
    });

    it("should show error for end chapter above total", () => {
      const { getByText, getByDisplayValue, queryByText } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      const input = getByDisplayValue("100");
      fireEvent.changeText(input, "101");

      fireEvent.press(getByText("Download"));
      expect(
        queryByText("Range must be between 1 and 100."),
      ).toBeTruthy();
    });

    it("should show error when start is greater than end", () => {
      const { getByText, getByDisplayValue, queryByText } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      const startInput = getByDisplayValue("1");
      const endInput = getByDisplayValue("100");
      fireEvent.changeText(startInput, "50");
      fireEvent.changeText(endInput, "10");

      fireEvent.press(getByText("Download"));
      expect(
        queryByText("Start chapter cannot be greater than end chapter."),
      ).toBeTruthy();
    });

    it("should call onDownload with correct values when valid", () => {
      const { getByText } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      fireEvent.press(getByText("Download"));
      expect(defaultProps.onDownload).toHaveBeenCalledWith(1, 100);
    });

    it("should call onDismiss after successful download", () => {
      const { getByText } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      fireEvent.press(getByText("Download"));
      expect(defaultProps.onDismiss).toHaveBeenCalledTimes(1);
    });

    it("should not call onDownload when input is invalid", () => {
      const { getByText, getByDisplayValue } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      const input = getByDisplayValue("1");
      fireEvent.changeText(input, "abc");

      fireEvent.press(getByText("Download"));
      expect(defaultProps.onDownload).not.toHaveBeenCalled();
      expect(defaultProps.onDismiss).not.toHaveBeenCalled();
    });

    it("should handle valid range in middle of chapters", () => {
      const { getByText, getByDisplayValue } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      const startInput = getByDisplayValue("1");
      const endInput = getByDisplayValue("100");
      fireEvent.changeText(startInput, "20");
      fireEvent.changeText(endInput, "30");

      fireEvent.press(getByText("Download"));
      expect(defaultProps.onDownload).toHaveBeenCalledWith(20, 30);
    });

    it("should handle range of single chapter", () => {
      const { getByText, getByDisplayValue } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      const startInput = getByDisplayValue("1");
      const endInput = getByDisplayValue("100");
      fireEvent.changeText(startInput, "50");
      fireEvent.changeText(endInput, "50");

      fireEvent.press(getByText("Download"));
      expect(defaultProps.onDownload).toHaveBeenCalledWith(50, 50);
    });

    it("should show preview for valid range", () => {
      const { getByText } = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );

      expect(getByText("Will download chapters 1\u2013100")).toBeTruthy();
    });
  });

  describe("Bookmark mode", () => {
    const bookmarkProps = {
      ...defaultProps,
      hasBookmark: true,
      bookmarkChapterNumber: 47,
    };

    const renderBookmarkMode = () => {
      const result = renderWithTheme(
        <DownloadRangeDialog {...bookmarkProps} />,
      );
      fireEvent.press(result.getByText("Bookmark"));
      return result;
    };

    it("should render bookmark info when in bookmark mode", () => {
      const { getByText } = renderBookmarkMode();

      expect(getByText("Bookmark at chapter 47")).toBeTruthy();
    });

    it("should initialize count to 150", () => {
      const { getByDisplayValue } = renderBookmarkMode();

      expect(getByDisplayValue("150")).toBeTruthy();
    });

    it("should download from chapter after bookmark", () => {
      const { getByText, getByDisplayValue } = renderBookmarkMode();

      fireEvent.changeText(getByDisplayValue("150"), "10");
      fireEvent.press(getByText("Download"));

      expect(defaultProps.onDownload).toHaveBeenCalledWith(48, 57);
    });

    it("should cap at total chapters", () => {
      const { getByText } = renderBookmarkMode();

      fireEvent.press(getByText("Download"));

      expect(defaultProps.onDownload).toHaveBeenCalledWith(48, 100);
    });

    it("should show error when bookmark is at last chapter", () => {
      const { getByText, queryByText } = renderWithTheme(
        <DownloadRangeDialog
          {...defaultProps}
          hasBookmark
          bookmarkChapterNumber={100}
        />,
      );
      fireEvent.press(getByText("Bookmark"));

      fireEvent.press(getByText("Download"));
      expect(
        queryByText("Bookmark is at the last chapter, nothing to download."),
      ).toBeTruthy();
    });

    it("should show error for invalid count", () => {
      const { getByText, getByDisplayValue, queryByText } = renderBookmarkMode();

      fireEvent.changeText(getByDisplayValue("150"), "abc");
      fireEvent.press(getByText("Download"));

      expect(
        queryByText("Please enter a valid number of chapters."),
      ).toBeTruthy();
    });
  });

  describe("Count mode", () => {
    const renderCountMode = () => {
      const result = renderWithTheme(
        <DownloadRangeDialog {...defaultProps} />,
      );
      fireEvent.press(result.getByText("Count"));
      return result;
    };

    it("should render from chapter and count inputs", () => {
      const { getByDisplayValue } = renderCountMode();

      expect(getByDisplayValue("1")).toBeTruthy();
      expect(getByDisplayValue("150")).toBeTruthy();
    });

    it("should download correct range from count", () => {
      const { getByText, getByDisplayValue } = renderCountMode();

      fireEvent.changeText(getByDisplayValue("150"), "50");
      fireEvent.press(getByText("Download"));

      expect(defaultProps.onDownload).toHaveBeenCalledWith(1, 50);
    });

    it("should cap at total chapters", () => {
      const { getByText } = renderCountMode();

      fireEvent.press(getByText("Download"));

      expect(defaultProps.onDownload).toHaveBeenCalledWith(1, 100);
    });

    it("should start from custom chapter with count", () => {
      const { getByText, getByDisplayValue, getAllByDisplayValue } =
        renderCountMode();

      const startInputs = getAllByDisplayValue("1");
      fireEvent.changeText(startInputs[0], "30");
      fireEvent.press(getByText("Download"));

      expect(defaultProps.onDownload).toHaveBeenCalledWith(30, 100);
    });

    it("should show error for invalid start", () => {
      const { getByText, getAllByDisplayValue, queryByText } = renderCountMode();

      const startInputs = getAllByDisplayValue("1");
      fireEvent.changeText(startInputs[0], "0");
      fireEvent.press(getByText("Download"));

      expect(
        queryByText("Start chapter must be between 1 and 100."),
      ).toBeTruthy();
    });

    it("should show error for invalid count", () => {
      const { getByText, getByDisplayValue, queryByText } = renderCountMode();

      fireEvent.changeText(getByDisplayValue("150"), "-5");
      fireEvent.press(getByText("Download"));

      expect(queryByText("Chapter count must be at least 1.")).toBeTruthy();
    });
  });

  it("should reset inputs when dialog becomes visible with different props", () => {
    const { rerender, getByDisplayValue } = renderWithTheme(
      <DownloadRangeDialog {...defaultProps} />,
    );

    expect(getByDisplayValue("1")).toBeTruthy();
    expect(getByDisplayValue("100")).toBeTruthy();

    rerender(
      <PaperProvider theme={mockTheme}>
        <DownloadRangeDialog {...defaultProps} totalChapters={200} />
      </PaperProvider>,
    );

    expect(getByDisplayValue("1")).toBeTruthy();
    expect(getByDisplayValue("200")).toBeTruthy();
  });
});
