import React, { useMemo } from 'react';
import { View, StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from 'react-native-paper';

export const ReaderContent = ({ webViewRef, processedContent }: { webViewRef: React.RefObject<WebView>, processedContent: string }) => {
    const theme = useTheme();

    const htmlContent = useMemo(() => {
        if (!processedContent) return '';
        
        const css = `
            body {
                background-color: ${theme.colors.surface};
                color: ${theme.colors.onSurface};
                font-family: sans-serif;
                padding: 16px;
                line-height: 1.6;
                font-size: 18px;
                padding-bottom: 64px;
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
    }, [processedContent, theme]);

    return (
        <View style={styles.container}>
            <WebView
                ref={webViewRef}
                originWhitelist={['*']}
                source={{ html: htmlContent }}
                style={{ backgroundColor: theme.colors.surface }}
            />
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
    },
});
