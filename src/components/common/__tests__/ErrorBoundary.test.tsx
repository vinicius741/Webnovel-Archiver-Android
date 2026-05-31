import React from "react";
import { render, screen, fireEvent } from "@testing-library/react-native";
import { ErrorBoundary } from "../ErrorBoundary";
import { Text, Button } from "react-native-paper";

// Component that throws an error
const ThrowError = ({ shouldThrow }: { shouldThrow: boolean }) => {
  if (shouldThrow) {
    throw new Error("Test error message");
  }
  return <Text>Normal content</Text>;
};

// Component with a button to trigger reset
const ResetButton = ({ onReset }: { onReset: () => void }) => (
  <Button onPress={onReset}>Reset</Button>
);

describe("ErrorBoundary", () => {
  // Suppress console.error for expected errors in tests
  const originalError = console.error;
  beforeAll(() => {
    console.error = jest.fn();
  });
  afterAll(() => {
    console.error = originalError;
  });

  it("should render children when there is no error", () => {
    render(
      <ErrorBoundary>
        <Text>Test content</Text>
      </ErrorBoundary>
    );

    expect(screen.getByText("Test content")).toBeTruthy();
  });

  it("should render fallback UI when an error occurs", () => {
    render(
      <ErrorBoundary>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText("Something went wrong")).toBeTruthy();
    expect(screen.getByText("An unexpected error occurred. You can try again or go back.")).toBeTruthy();
  });

  it("should display error message in fallback UI", () => {
    render(
      <ErrorBoundary>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText("Test error message")).toBeTruthy();
  });

  it("should display context label when provided", () => {
    render(
      <ErrorBoundary contextLabel="Reader">
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText("in Reader")).toBeTruthy();
  });

  it("should call onError callback when error occurs", () => {
    const onError = jest.fn();

    render(
      <ErrorBoundary onError={onError}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith(
      expect.any(Error),
      expect.objectContaining({ componentStack: expect.any(String) })
    );
  });

  it("should reset error state when Try Again button is pressed", () => {
    let shouldThrow = true;

    const TestComponent = () => {
      if (shouldThrow) {
        throw new Error("Test error");
      }
      return <Text>Recovered content</Text>;
    };

    const { rerender } = render(
      <ErrorBoundary>
        <TestComponent />
      </ErrorBoundary>
    );

    // Verify error state
    expect(screen.getByText("Something went wrong")).toBeTruthy();

    // Fix the error condition
    shouldThrow = false;

    // Press Try Again button
    const tryAgainButton = screen.getByText("Try Again");
    fireEvent.press(tryAgainButton);

    // Should now show the recovered content
    expect(screen.getByText("Recovered content")).toBeTruthy();
  });

  it("should call onReset callback when resetting", () => {
    const onReset = jest.fn();

    render(
      <ErrorBoundary onReset={onReset}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    const tryAgainButton = screen.getByText("Try Again");
    fireEvent.press(tryAgainButton);

    expect(onReset).toHaveBeenCalledTimes(1);
  });

  it("should render custom fallback when provided", () => {
    const customFallback = <Text>Custom error UI</Text>;

    render(
      <ErrorBoundary fallback={customFallback}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>
    );

    expect(screen.getByText("Custom error UI")).toBeTruthy();
    expect(screen.queryByText("Something went wrong")).toBeNull();
  });

  it("should recover and render children after error is fixed", () => {
    let shouldThrow = true;

    const TestComponent = () => {
      if (shouldThrow) {
        throw new Error("Test error");
      }
      return <Text>Success</Text>;
    };

    const { rerender } = render(
      <ErrorBoundary>
        <TestComponent />
      </ErrorBoundary>
    );

    // Verify error state
    expect(screen.getByText("Something went wrong")).toBeTruthy();

    // Fix the error
    shouldThrow = false;

    // Trigger reset
    const tryAgainButton = screen.getByText("Try Again");
    fireEvent.press(tryAgainButton);

    // Should show success content
    expect(screen.getByText("Success")).toBeTruthy();
  });
});
