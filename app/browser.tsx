import React, { useState, useRef, useCallback, useEffect } from "react";
import {
  StyleSheet,
  View,
  TextInput,
  ActivityIndicator,
  Keyboard,
  BackHandler,
  Linking,
} from "react-native";
import { WebView, WebViewNavigation } from "react-native-webview";
import {
  Text,
  IconButton,
  Portal,
  Dialog,
  useTheme,
} from "react-native-paper";
import { useRouter, useLocalSearchParams } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { ScreenContainer } from "../src/components/ScreenContainer";
import { AppButton } from "../src/components/theme/AppButton";
import { BrowserLanding } from "../src/components/browser/BrowserLanding";
import { TabSelectionList } from "../src/components/tabs/TabSelectionList";
import { storageService } from "../src/services/StorageService";
import { useTabs } from "../src/hooks/useTabs";
import { useAppAlert } from "../src/context/AlertContext";
import {
  buildStoryForAdd,
  prepareStorySyncData,
} from "../src/services/story/storySyncOrchestrator";

// Helper to determine if a URL is a story detail page we can download.
const isNovelUrl = (url: string): boolean => {
  if (!url) return false;

  // Scribble Hub series: https://www.scribblehub.com/series/12345/title
  if (/https?:\/\/(?:www\.)?scribblehub\.com\/series\/\d+/i.test(url)) {
    return true;
  }

  // Royal Road fiction: https://www.royalroad.com/fiction/12345/title
  if (/https?:\/\/(?:www\.)?royalroad\.com\/fiction\/\d+/i.test(url)) {
    return !url.includes("/chapter/");
  }

  return false;
};

// Helper to format/validate URL or convert to Google search query
const resolveUrl = (input: string): string => {
  const trimmed = input.trim();
  if (!trimmed) return "";

  // Check if it looks like a URL
  const isUrl = /^(https?:\/\/)?([\da-z.-]+)\.([a-z.]{2,6})([/\w .-]*)*\/?(?:\?[^#]*)?(?:#.*)?$/i.test(trimmed);
  if (isUrl) {
    if (/^https?:\/\//i.test(trimmed)) {
      return trimmed;
    }
    return `https://${trimmed}`;
  }

  // If not a URL, search on Google
  return `https://www.google.com/search?q=${encodeURIComponent(trimmed)}`;
};

const isGoogleAuthUrl = (url: string): boolean => {
  if (!url) return false;

  try {
    const { hostname, pathname } = new URL(url);
    const normalizedHost = hostname.toLowerCase();

    return (
      normalizedHost === "accounts.google.com" ||
      normalizedHost.endsWith(".accounts.google.com") ||
      (normalizedHost === "google.com" || normalizedHost.endsWith(".google.com")) &&
        pathname.startsWith("/o/oauth2")
    );
  } catch {
    return false;
  }
};

export default function SourceBrowserScreen() {
  const router = useRouter();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const params = useLocalSearchParams<{ url?: string }>();
  const { showAlert } = useAppAlert();
  const { tabs, hasCustomTabs } = useTabs();

  // Webview and Address bar state
  const webViewRef = useRef<WebView>(null);
  const [showBrowser, setShowBrowser] = useState(false);
  const [inputUrl, setInputUrl] = useState("");
  const [currentUrl, setCurrentUrl] = useState("");
  const [canGoBack, setCanGoBack] = useState(false);
  const [canGoForward, setCanGoForward] = useState(false);
  const [loading, setLoading] = useState(false);
  const [webProgress, setWebProgress] = useState(0);

  // Dialog and Import states
  const [showDownloadDialog, setShowDownloadDialog] = useState(false);
  const [selectedTabId, setSelectedTabId] = useState<string | undefined>(undefined);
  const [importing, setImporting] = useState(false);
  const [importStatus, setImportStatus] = useState("");

  // Initialize browser if a URL param is passed
  useEffect(() => {
    if (params.url) {
      setInputUrl(params.url);
      setCurrentUrl(params.url);
      setShowBrowser(true);
    }
  }, [params.url]);

  // Handle hardware back button on Android
  useEffect(() => {
    const handleAndroidBack = () => {
      if (showBrowser) {
        if (canGoBack && webViewRef.current) {
          webViewRef.current.goBack();
        } else {
          setShowBrowser(false);
        }
        return true;
      }
      return false; // let system handle it (navigate back)
    };

    const subscription = BackHandler.addEventListener("hardwareBackPress", handleAndroidBack);
    return () => subscription.remove();
  }, [showBrowser, canGoBack]);

  const handleNavigate = useCallback((urlToLoad: string) => {
    const resolved = resolveUrl(urlToLoad);
    if (resolved) {
      setInputUrl(resolved);
      setCurrentUrl(resolved);
      setShowBrowser(true);
      Keyboard.dismiss();
    }
  }, []);

  const handleGoBack = useCallback(() => {
    if (canGoBack && webViewRef.current) {
      webViewRef.current.goBack();
    } else {
      setShowBrowser(false);
    }
  }, [canGoBack]);

  const handleGoForward = useCallback(() => {
    if (canGoForward && webViewRef.current) {
      webViewRef.current.goForward();
    }
  }, [canGoForward]);

  const handleRefresh = useCallback(() => {
    if (webViewRef.current) {
      webViewRef.current.reload();
    }
  }, []);

  const openExternalBrowser = useCallback((url: string) => {
    Linking.openURL(url).catch((error) => {
      console.error("[Browser] Failed to open external browser", error);
      showAlert("Open Browser Failed", "Could not open this page in your device browser.");
    });
  }, [showAlert]);

  const handleOpenCurrentUrlExternally = useCallback(() => {
    const url = currentUrl || inputUrl;
    if (url) {
      openExternalBrowser(url);
    }
  }, [currentUrl, inputUrl, openExternalBrowser]);

  const handleHome = useCallback(() => {
    router.dismissAll();
    router.replace("/");
  }, [router]);

  const handleNavigationStateChange = useCallback((navState: WebViewNavigation) => {
    setCurrentUrl(navState.url);
    setInputUrl(navState.url);
    setCanGoBack(navState.canGoBack);
    setCanGoForward(navState.canGoForward);
    setLoading(navState.loading);
  }, []);

  const handleShouldStartLoad = useCallback((request: { url: string }) => {
    if (isGoogleAuthUrl(request.url)) {
      openExternalBrowser(request.url);
      return false;
    }

    return true;
  }, [openExternalBrowser]);

  const handleImportStory = async () => {
    if (!currentUrl) return;

    setImporting(true);
    setImportStatus("Initializing connection...");
    try {
      console.log("[Browser] Importing story from:", currentUrl);
      
      const prepared = await prepareStorySyncData({
        sourceUrl: currentUrl,
        loadExistingStory: (storyId) => storageService.getStory(storyId),
        onStatus: setImportStatus,
        onProgress: setImportStatus,
      });

      setImportStatus("Saving novel metadata...");
      const story = buildStoryForAdd(prepared, selectedTabId);
      await storageService.addStory(story);

      setShowDownloadDialog(false);
      
      showAlert("Success", `Added "${prepared.metadata.title}" to library.`, [
        {
          text: "View Story",
          onPress: () => {
            // Dismiss browser and go to details
            router.replace(`/details/${story.id}`);
          },
        },
        {
          text: "Keep Browsing",
          style: "cancel",
        },
      ]);
    } catch (e) {
      console.error("[Browser] Failed to import story", e);
      showAlert(
        "Import Failed",
        `Failed to import story. ${e instanceof Error ? e.message : String(e)}`,
      );
    } finally {
      setImporting(false);
      setImportStatus("");
    }
  };

  const isNovel = isNovelUrl(currentUrl);

  return (
    <ScreenContainer edges={["bottom", "left", "right"]} style={styles.screen}>
      {/* Custom Browser Header */}
      <View style={[styles.header, { paddingTop: insets.top }]}>
        <IconButton
          icon={showBrowser ? "arrow-left" : "close"}
          onPress={showBrowser ? handleGoBack : () => router.back()}
          size={24}
        />
        {showBrowser && (
          <IconButton
            icon="arrow-right"
            onPress={handleGoForward}
            disabled={!canGoForward}
            size={24}
            style={styles.actionButton}
          />
        )}
        <TextInput
          style={[styles.addressInput, { backgroundColor: theme.colors.surfaceVariant, color: theme.colors.onSurface }]}
          placeholder="Search or enter webnovel address..."
          placeholderTextColor={theme.colors.onSurfaceVariant}
          value={inputUrl}
          onChangeText={setInputUrl}
          onSubmitEditing={() => handleNavigate(inputUrl)}
          keyboardType="url"
          autoCapitalize="none"
          autoCorrect={false}
          returnKeyType="go"
        />
        {showBrowser ? (
          <View style={styles.browserActions}>
            <IconButton icon="refresh" size={22} onPress={handleRefresh} style={styles.actionButton} />
            <IconButton
              icon="open-in-new"
              size={22}
              onPress={handleOpenCurrentUrlExternally}
              style={styles.actionButton}
              testID="open-external-button"
            />
            <IconButton icon="home" size={22} onPress={handleHome} style={styles.actionButton} />
            {isNovel && (
              <IconButton
                icon="download"
                size={22}
                onPress={() => setShowDownloadDialog(true)}
                style={styles.actionButton}
                testID="download-button"
              />
            )}
          </View>
        ) : (
          <IconButton
            icon="magnify"
            size={24}
            onPress={() => handleNavigate(inputUrl)}
            disabled={!inputUrl}
          />
        )}
      </View>

      {/* Web loading indicator */}
      {showBrowser && loading && (
        <View style={[styles.progressBar, { backgroundColor: theme.colors.primaryContainer }]}>
          <View
            style={[
              styles.progressBarFill,
              {
                backgroundColor: theme.colors.primary,
                width: `${Math.max(webProgress * 100, 10)}%`,
              },
            ]}
          />
        </View>
      )}

      {/* Main Content Area */}
      <View style={styles.contentContainer}>
        {showBrowser ? (
          <WebView
            ref={webViewRef}
            source={{ uri: currentUrl }}
            onNavigationStateChange={handleNavigationStateChange}
            onShouldStartLoadWithRequest={handleShouldStartLoad}
            onLoadProgress={({ nativeEvent }) => setWebProgress(nativeEvent.progress)}
            style={styles.webView}
            javaScriptEnabled={true}
            domStorageEnabled={true}
            startInLoadingState={true}
            renderLoading={() => (
              <View style={styles.center}>
                <ActivityIndicator size="large" />
              </View>
            )}
          />
        ) : (
          <BrowserLanding onNavigate={handleNavigate} />
        )}
      </View>

      {/* Dialog for selecting Tab & Importing */}
      <Portal>
        <Dialog visible={showDownloadDialog} onDismiss={() => !importing && setShowDownloadDialog(false)}>
          <Dialog.Title>{importing ? "Importing Story" : "Download Story"}</Dialog.Title>
          <Dialog.Content>
            {importing ? (
              <View style={styles.importLoadingContainer}>
                <ActivityIndicator size="large" style={{ marginBottom: 16 }} />
                <Text variant="bodyMedium" style={styles.importStatusText}>
                  {importStatus}
                </Text>
              </View>
            ) : (
              <View>
                <Text variant="bodyMedium" style={styles.dialogDescription}>
                  Would you like to import this webnovel into your library?
                </Text>

                {hasCustomTabs && (
                  <TabSelectionList
                    tabs={tabs}
                    selectedTabId={selectedTabId}
                    onSelectTab={setSelectedTabId}
                  />
                )}
              </View>
            )}
          </Dialog.Content>
          {!importing && (
            <Dialog.Actions>
              <AppButton onPress={() => setShowDownloadDialog(false)} mode="text">
                Cancel
              </AppButton>
              <AppButton onPress={handleImportStory}>
                Import
              </AppButton>
            </Dialog.Actions>
          )}
        </Dialog>
      </Portal>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    paddingHorizontal: 0,
    paddingVertical: 0,
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 8,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: "rgba(0, 0, 0, 0.05)",
  },
  addressInput: {
    flex: 1,
    height: 40,
    borderRadius: 20,
    paddingHorizontal: 16,
    fontSize: 14,
    marginRight: 4,
  },
  browserActions: {
    flexDirection: "row",
    alignItems: "center",
  },
  actionButton: {
    margin: 0,
  },
  progressBar: {
    height: 3,
    width: "100%",
  },
  progressBarFill: {
    height: "100%",
  },
  contentContainer: {
    flex: 1,
  },
  webView: {
    flex: 1,
  },
  center: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  importLoadingContainer: {
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 20,
  },
  importStatusText: {
    textAlign: "center",
    opacity: 0.8,
  },
  dialogDescription: {
    marginBottom: 16,
    lineHeight: 20,
  },
});
