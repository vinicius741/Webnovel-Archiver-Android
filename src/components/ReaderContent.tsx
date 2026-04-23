import React, { useMemo } from "react";
import { View, StyleSheet } from "react-native";
import { WebView } from "react-native-webview";
import { useTheme } from "react-native-paper";

interface ReaderContentProps {
  webViewRef: React.RefObject<WebView>;
  processedContent: string;
  maxWidth?: number;
  contentPadding?: number;
  bottomPadding?: number;
}

export const ReaderContent = ({
  webViewRef,
  processedContent,
  maxWidth,
  contentPadding = 20,
  bottomPadding = 112,
}: ReaderContentProps) => {
  const theme = useTheme();

  const htmlContent = useMemo(() => {
    if (!processedContent) return "";

    const css = `
            body {
                margin: 0;
                background-color: ${theme.colors.surface};
                color: ${theme.colors.onSurface};
                font-family: sans-serif;
                padding: ${contentPadding}px;
                line-height: 1.6;
                font-size: 18px;
                padding-bottom: ${bottomPadding}px;
                box-sizing: border-box;
                -webkit-text-size-adjust: 100%;
            }
            img {
                max-width: 100%;
                height: auto;
            }
            .tts-active {
                background-color: rgba(255, 235, 59, 0.3);
                border-left: 3px solid ${theme.colors.primary};
                padding-left: 4px;
            }
        `;

    return `
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
                <style>${css}</style>
            </head>
            <body>
                ${processedContent}
            </body>
            </html>
        `;
  }, [bottomPadding, contentPadding, processedContent, theme]);

  return (
    <View style={[styles.container, maxWidth ? { maxWidth } : null]}>
      <WebView
        ref={webViewRef}
        originWhitelist={["*"]}
        source={{ html: htmlContent }}
        style={[styles.webView, { backgroundColor: theme.colors.surface }]}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    width: "100%",
    alignSelf: "center",
  },
  webView: {
    flex: 1,
  },
});
