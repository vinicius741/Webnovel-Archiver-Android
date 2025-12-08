import React, { forwardRef, useImperativeHandle, useRef } from 'react';
import { View, StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';

interface HeadlessWebViewProps {
  onHtmlRetrieved?: (html: string) => void;
}

export interface HeadlessWebViewRef {
  loadUrl: (url: string) => void;
}

const HeadlessWebView = forwardRef<HeadlessWebViewRef, HeadlessWebViewProps>(({ onHtmlRetrieved }, ref) => {
  const webViewRef = useRef<WebView>(null);
  
  useImperativeHandle(ref, () => ({
    loadUrl: (url: string) => {
        // Use a dummy query param to force reload if needed, or just set source
        webViewRef.current?.injectJavaScript(`window.location.href = "${url}"; true;`); 
    }
  }));

  const injectedJS = `
    setTimeout(function() {
      window.ReactNativeWebView.postMessage(document.documentElement.outerHTML);
    }, 2000); // Wait 2s for Cloudflare/JS to settle
    true;
  `;

  return (
    <View style={styles.hidden}>
      <WebView
        ref={webViewRef}
        source={{ uri: 'about:blank' }}
        onMessage={(event) => {
          if (onHtmlRetrieved) {
            onHtmlRetrieved(event.nativeEvent.data);
          }
        }}
        injectedJavaScript={injectedJS}
        javaScriptEnabled={true}
        userAgent="Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
      />
    </View>
  );
});

const styles = StyleSheet.create({
  hidden: {
    height: 0,
    width: 0,
    opacity: 0,
    position: 'absolute',
  },
});

export default HeadlessWebView;
