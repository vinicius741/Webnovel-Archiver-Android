import React from "react";
import { render, fireEvent } from "@testing-library/react-native";
import { StoryDescription } from "../details/StoryDescription";
import { ThemeProvider } from "react-native-paper";
import * as Clipboard from "expo-clipboard";

jest.mock("expo-clipboard", () => ({
  setStringAsync: jest.fn().mockResolvedValue(undefined),
}));

jest.mock("react-native-paper", () => ({
  ...jest.requireActual("react-native-paper"),
  useTheme: jest.fn().mockReturnValue({
    colors: {
      primary: "#6200ee",
    },
  }),
}));

describe("StoryDescription", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });
  const mockTheme = {
    colors: {
      primary: "#6200ee",
    },
  };

  const defaultProps = {
    description: "This is a test story description.",
  };

  const renderWithTheme = (component: React.ReactElement) => {
    return render(
      <ThemeProvider theme={mockTheme as any}>{component}</ThemeProvider>,
    );
  };

  it("should render description text", () => {
    const { getByText } = renderWithTheme(
      <StoryDescription {...defaultProps} />,
    );

    expect(getByText("This is a test story description.")).toBeTruthy();
  });

  it("should not render when description is undefined", () => {
    const { queryByText } = renderWithTheme(
      <StoryDescription description={undefined} />,
    );

    expect(queryByText("This is a test story description.")).toBeNull();
  });

  it("should not render when description is empty string", () => {
    const { queryByText } = renderWithTheme(
      <StoryDescription description="" />,
    );

    expect(queryByText("")).toBeNull();
  });

  it("should render with proper style", () => {
    const { getByText } = renderWithTheme(
      <StoryDescription {...defaultProps} />,
    );

    const description = getByText("This is a test story description.");
    expect(description).toBeTruthy();
  });

  it("should truncate long description and show read more button", () => {
    const longDescription = "A".repeat(300);
    const { getByTestId, queryByText } = renderWithTheme(
      <StoryDescription description={longDescription} />,
    );

    expect(queryByText(longDescription)).toBeNull();
    expect(getByTestId("read-more-button")).toBeTruthy();
    expect(getByTestId("read-more-text").children[0]).toBe("Read more");
  });

  it("should expand to show full description when read more is clicked", () => {
    const longDescription = "A".repeat(300);
    const { getByTestId, getByText } = renderWithTheme(
      <StoryDescription description={longDescription} />,
    );

    fireEvent.press(getByTestId("read-more-button"));

    expect(getByText(longDescription)).toBeTruthy();
    expect(getByTestId("read-more-text").children[0]).toBe("Show less");
  });

  it("should collapse when show less is clicked", () => {
    const longDescription = "A".repeat(300);
    const { getByTestId, queryByText } = renderWithTheme(
      <StoryDescription description={longDescription} />,
    );

    fireEvent.press(getByTestId("read-more-button"));
    fireEvent.press(getByTestId("read-more-button"));

    expect(queryByText(longDescription)).toBeNull();
    expect(getByTestId("read-more-text").children[0]).toBe("Read more");
  });

  it("should not show read more button for short descriptions", () => {
    const { queryByTestId } = renderWithTheme(
      <StoryDescription {...defaultProps} />,
    );

    expect(queryByTestId("read-more-button")).toBeNull();
  });

  it("should use custom maxLength when provided", () => {
    const description = "A".repeat(150);
    const { queryByTestId, getByTestId } = renderWithTheme(
      <StoryDescription description={description} maxLength={100} />,
    );

    expect(getByTestId("read-more-button")).toBeTruthy();
  });

  it("should call Clipboard.setStringAsync on double press", async () => {
    const { getByText } = renderWithTheme(
      <StoryDescription {...defaultProps} />,
    );

    const description = getByText("This is a test story description.");

    await fireEvent.press(description);
    await fireEvent.press(description);

    expect(Clipboard.setStringAsync).toHaveBeenCalledWith(
      "This is a test story description.",
    );
  });

  it("should not call Clipboard.setStringAsync on single press", async () => {
    const { getByText } = renderWithTheme(
      <StoryDescription {...defaultProps} />,
    );

    const description = getByText("This is a test story description.");

    await fireEvent.press(description);

    expect(Clipboard.setStringAsync).not.toHaveBeenCalled();
  });

  it("should handle multi-line description", () => {
    const multiLineDescription = "Line 1\nLine 2\nLine 3";
    const { getByText } = renderWithTheme(
      <StoryDescription description={multiLineDescription} />,
    );

    expect(getByText(multiLineDescription)).toBeTruthy();
  });

  it("should handle description with special characters", () => {
    const specialDescription =
      "Test with <html> & \"quotes\" and 'apostrophes'";
    const { getByText } = renderWithTheme(
      <StoryDescription description={specialDescription} />,
    );

    expect(getByText(specialDescription)).toBeTruthy();
  });

  it("should handle description with emojis", () => {
    const emojiDescription = "Test with emojis 📚✨🎉";
    const { getByText } = renderWithTheme(
      <StoryDescription description={emojiDescription} />,
    );

    expect(getByText(emojiDescription)).toBeTruthy();
  });

  it("should render centered description", () => {
    const { getByText } = renderWithTheme(
      <StoryDescription {...defaultProps} />,
    );

    const description = getByText("This is a test story description.");
    expect(description).toBeTruthy();
  });
});
