import React, { Component, ErrorInfo, ReactNode } from "react";
import {
  StyleSheet,
  View,
  ScrollView,
  Pressable,
  Text,
  StatusBar,
} from "react-native";

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

/**
 * Root-level error boundary that wraps the entire application.
 * This boundary sits OUTSIDE the theme provider, so it uses plain
 * React Native components with hardcoded colors as a last-resort
 * recovery screen.
 *
 * Catches any error not handled by a nested screen-level boundary.
 */
export class RootErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null,
  };

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("[RootErrorBoundary] Fatal uncaught error:", error, errorInfo);
  }

  private handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  public render() {
    if (this.state.hasError) {
      return (
        <View style={styles.container}>
          <StatusBar
            backgroundColor="#121212"
            barStyle="light-content"
          />
          <ScrollView
            contentContainerStyle={styles.scrollContent}
            showsVerticalScrollIndicator={false}
          >
            <View style={styles.iconCircle}>
              <Text style={styles.iconText}>!</Text>
            </View>

            <Text style={styles.title}>App Error</Text>

            <Text style={styles.description}>
              The application encountered an unexpected error.{"\n"}
              Tap "Restart" to try again.
            </Text>

            {this.state.error && (
              <View style={styles.errorBox}>
                <Text style={styles.errorLabel}>ERROR DETAILS</Text>
                <Text style={styles.errorMessage} numberOfLines={8}>
                  {this.state.error.message}
                </Text>
              </View>
            )}

            <Pressable
              style={({ pressed }) => [
                styles.button,
                pressed && styles.buttonPressed,
              ]}
              onPress={this.handleReset}
            >
              <Text style={styles.buttonText}>↻ Restart</Text>
            </Pressable>
          </ScrollView>
        </View>
      );
    }

    return this.props.children;
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#121212",
  },
  scrollContent: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 32,
    paddingVertical: 48,
  },
  iconCircle: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: "#B3261E",
    alignItems: "center",
    justifyContent: "center",
    marginBottom: 24,
  },
  iconText: {
    fontSize: 42,
    fontWeight: "700",
    color: "#FFFFFF",
  },
  title: {
    fontSize: 24,
    fontWeight: "700",
    color: "#E6E1E5",
    textAlign: "center",
    marginBottom: 8,
  },
  description: {
    fontSize: 15,
    color: "#CAC4D0",
    textAlign: "center",
    lineHeight: 22,
    marginBottom: 28,
  },
  errorBox: {
    width: "100%",
    padding: 16,
    borderRadius: 12,
    backgroundColor: "#1E1E1E",
    borderColor: "#49454F",
    borderWidth: 1,
    marginBottom: 28,
  },
  errorLabel: {
    fontSize: 11,
    fontWeight: "600",
    color: "#CAC4D0",
    letterSpacing: 0.5,
    marginBottom: 8,
  },
  errorMessage: {
    fontSize: 12,
    fontFamily: "monospace",
    color: "#F2B8B5",
    lineHeight: 18,
  },
  button: {
    backgroundColor: "#6750A4",
    paddingHorizontal: 32,
    paddingVertical: 14,
    borderRadius: 20,
    minWidth: 160,
    alignItems: "center",
    elevation: 3,
  },
  buttonPressed: {
    opacity: 0.85,
    transform: [{ scale: 0.97 }],
  },
  buttonText: {
    fontSize: 16,
    fontWeight: "600",
    color: "#FFFFFF",
  },
});
