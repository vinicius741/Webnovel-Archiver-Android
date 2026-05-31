import React, { Component, ErrorInfo, ReactNode } from "react";
import { StyleSheet, View, ScrollView, Pressable } from "react-native";
import { Text, Button, useTheme } from "react-native-paper";
import { MaterialCommunityIcons } from "@expo/vector-icons";

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
  onError?: (error: Error, errorInfo: ErrorInfo) => void;
  onReset?: () => void;
  /** Label shown in the boundary context, e.g. "Reader", "Settings" */
  contextLabel?: string;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

/**
 * React Error Boundary that catches rendering errors in child components
 * and displays a recovery UI instead of crashing the entire app.
 */
export class ErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null,
  };

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error(
      `[ErrorBoundary${this.props.contextLabel ? `:${this.props.contextLabel}` : ""}] Uncaught error:`,
      error,
      errorInfo,
    );
    this.props.onError?.(error, errorInfo);
  }

  private handleReset = () => {
    this.props.onReset?.();
    this.setState({ hasError: false, error: null });
  };

  public render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }

      return (
        <ErrorFallbackUI
          error={this.state.error}
          contextLabel={this.props.contextLabel}
          onReset={this.handleReset}
        />
      );
    }

    return this.props.children;
  }
}

interface FallbackProps {
  error: Error | null;
  contextLabel?: string;
  onReset: () => void;
}

/**
 * Default fallback UI displayed when an error boundary catches an error.
 * Uses react-native-paper theming for consistent appearance.
 */
function ErrorFallbackUIInner({ error, contextLabel, onReset }: FallbackProps) {
  const theme = useTheme();

  return (
    <View
      style={[
        styles.container,
        { backgroundColor: theme.colors.background },
      ]}
    >
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        <MaterialCommunityIcons
          name="alert-circle-outline"
          size={72}
          color={theme.colors.error}
          style={styles.icon}
        />

        <Text
          variant="headlineMedium"
          style={[styles.title, { color: theme.colors.onBackground }]}
        >
          Something went wrong
        </Text>

        {contextLabel && (
          <Text
            variant="bodyLarge"
            style={[styles.context, { color: theme.colors.onSurfaceVariant }]}
          >
            in {contextLabel}
          </Text>
        )}

        <Text
          variant="bodyMedium"
          style={[
            styles.description,
            { color: theme.colors.onSurfaceVariant },
          ]}
        >
          An unexpected error occurred. You can try again or go back.
        </Text>

        {error && (
          <View
            style={[
              styles.errorBox,
              {
                backgroundColor: theme.colors.elevation.level1,
                borderColor: theme.colors.outlineVariant,
              },
            ]}
          >
            <Text
              variant="bodySmall"
              style={[
                styles.errorLabel,
                { color: theme.colors.onSurfaceVariant },
              ]}
            >
              Error details
            </Text>
            <Text
              variant="bodySmall"
              style={[
                styles.errorMessage,
                { color: theme.colors.error },
              ]}
              numberOfLines={6}
            >
              {error.message}
            </Text>
          </View>
        )}

        <View style={styles.actions}>
          <Button
            mode="contained"
            onPress={onReset}
            icon="refresh"
            style={styles.button}
            contentStyle={styles.buttonContent}
          >
            Try Again
          </Button>
        </View>
      </ScrollView>
    </View>
  );
}

/**
 * Wraps the inner fallback UI with the theme provider so it works
 * even when used as a standalone component.
 */
function ErrorFallbackUI(props: FallbackProps) {
  return <ErrorFallbackUIInner {...props} />;
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scrollContent: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 32,
    paddingVertical: 48,
  },
  icon: {
    marginBottom: 16,
  },
  title: {
    textAlign: "center",
    marginBottom: 4,
  },
  context: {
    textAlign: "center",
    marginBottom: 12,
    fontStyle: "italic",
  },
  description: {
    textAlign: "center",
    marginBottom: 24,
    lineHeight: 22,
  },
  errorBox: {
    width: "100%",
    padding: 16,
    borderRadius: 12,
    borderWidth: 1,
    marginBottom: 24,
  },
  errorLabel: {
    marginBottom: 6,
    fontWeight: "600",
    textTransform: "uppercase",
    letterSpacing: 0.5,
    fontSize: 11,
  },
  errorMessage: {
    fontFamily: "monospace",
    lineHeight: 18,
  },
  actions: {
    flexDirection: "row",
    gap: 12,
  },
  button: {
    minWidth: 140,
  },
  buttonContent: {
    paddingVertical: 4,
  },
});

export { ErrorFallbackUI as ErrorFallbackScreen };
