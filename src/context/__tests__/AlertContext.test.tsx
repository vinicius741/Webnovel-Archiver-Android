import React from "react";
import { render, fireEvent, act } from "@testing-library/react-native";
import { MD3LightTheme, PaperProvider } from "react-native-paper";
import { AlertProvider, useAppAlert } from "../AlertContext";
import { Button } from "react-native";

// Mock the theme hooks in a unique way to avoid duplication matching
jest.mock("../../theme/useAppTheme", () => {
  const paper = jest.requireActual("react-native-paper");
  return {
    useAppTheme: () => ({
      ...paper.MD3LightTheme,
      buttonDefaults: {
        mode: "contained-tonal",
        textTransform: "none",
        borderWidth: 0,
        buttonHeight: 42,
      },
      shapes: { dialogRadius: 16 },
    }),
  };
});

const AlertTrigger = () => {
  const { showAlert } = useAppAlert();
  const trigger = () => {
    showAlert("Test Title", "Test Message", [
      { text: "Cancel", style: "cancel" },
      { text: "Confirm", style: "destructive" },
    ]);
  };
  return <Button testID="trigger-alert" title="Show" onPress={trigger} />;
};

describe("AlertProvider", () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  const renderWithProviders = () => {
    return render(
      <PaperProvider theme={MD3LightTheme}>
        <AlertProvider>
          <AlertTrigger />
        </AlertProvider>
      </PaperProvider>,
    );
  };

  it("should not render the portal or dialog initially", () => {
    const { queryByText } = renderWithProviders();
    expect(queryByText("Test Title")).toBeNull();
    expect(queryByText("Test Message")).toBeNull();
  });

  it("should mount the portal and show dialog when showAlert is called", () => {
    const { getByTestId, queryByText } = renderWithProviders();
    fireEvent.press(getByTestId("trigger-alert"));

    expect(queryByText("Test Title")).toBeTruthy();
    expect(queryByText("Test Message")).toBeTruthy();
    expect(queryByText("Cancel")).toBeTruthy();
    expect(queryByText("Confirm")).toBeTruthy();
  });

  it("should unmount the portal after a delay when hideAlert is triggered", () => {
    const { getByTestId, queryByText } = renderWithProviders();

    // Show alert
    fireEvent.press(getByTestId("trigger-alert"));
    expect(queryByText("Test Title")).toBeTruthy();

    // Dismiss it
    fireEvent.press(queryByText("Cancel")!);
    expect(queryByText("Test Title")).toBeTruthy();

    // Fast-forward 300ms
    act(() => {
      jest.advanceTimersByTime(300);
    });

    expect(queryByText("Test Title")).toBeNull();
  });

  it("should clear previous timeout when a new alert is shown quickly", () => {
    const { getByTestId, queryByText } = renderWithProviders();

    // Show alert
    fireEvent.press(getByTestId("trigger-alert"));
    expect(queryByText("Test Title")).toBeTruthy();

    // Dismiss
    fireEvent.press(queryByText("Cancel")!);

    // Re-show immediately
    fireEvent.press(getByTestId("trigger-alert"));

    act(() => {
      jest.advanceTimersByTime(300);
    });

    // Should still be mounted because the new show cleared the timeout
    expect(queryByText("Test Title")).toBeTruthy();
  });
});
