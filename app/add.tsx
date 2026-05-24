import React, { useState, useCallback } from "react";
import { StyleSheet, View, ScrollView } from "react-native";
import {
  TextInput,
  Text,
  IconButton,
  Divider,
} from "react-native-paper";
import { useRouter } from "expo-router";
import { AppButton } from "../src/components/theme/AppButton";
import { ScreenContainer } from "../src/components/common/ScreenContainer";
import { useAddStory } from "../src/hooks/library/useAddStory";
import { useScreenLayout } from "../src/hooks/common/useScreenLayout";
import { useTabs } from "../src/hooks/library/useTabs";
import { SourceCard } from "../src/components/browser/SourceCard";
import { TabSelectionList } from "../src/components/tabs/TabSelectionList";

type AdaptiveLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
};

export default function AddStoryScreen() {
  const router = useRouter();
  const screenLayout = useScreenLayout() as AdaptiveLayout;
  const widthClass =
    screenLayout.widthClass ??
    (screenLayout.screenWidth >= 840
      ? "expanded"
      : screenLayout.screenWidth >= 600
        ? "medium"
        : "compact");
  const { url, setUrl, loading, statusMessage, handlePaste, handleAdd } =
    useAddStory();
  const { tabs, hasCustomTabs } = useTabs();
  const [selectedTabId, setSelectedTabId] = useState<string | undefined>(undefined);

  const onAdd = useCallback(() => {
    void handleAdd(selectedTabId);
  }, [handleAdd, selectedTabId]);

  const contentMaxWidth =
    widthClass === "expanded" ? 840 : widthClass === "medium" ? 720 : undefined;

  return (
    <ScreenContainer edges={["bottom", "left", "right"]} style={styles.screen}>
      <ScrollView contentContainerStyle={styles.scrollContent}>
        <View
          style={[
            styles.form,
            contentMaxWidth ? { maxWidth: contentMaxWidth } : undefined,
          ]}
        >
          <Text variant="titleMedium" style={styles.label}>
            Webnovel URL
          </Text>
          <View style={styles.inputContainer}>
            <TextInput
              mode="outlined"
              placeholder="Royal Road or Scribble Hub story URL"
              value={url}
              onChangeText={setUrl}
              autoCapitalize="none"
              keyboardType="url"
              style={styles.input}
            />
            <IconButton
              icon="content-paste"
              mode="contained-tonal"
              onPress={handlePaste}
              style={styles.pasteButton}
            />
          </View>

          {hasCustomTabs && (
            <TabSelectionList
              tabs={tabs}
              selectedTabId={selectedTabId}
              onSelectTab={setSelectedTabId}
            />
          )}

          <AppButton
            onPress={onAdd}
            loading={loading}
            disabled={loading || !url}
            style={styles.button}
          >
            Fetch Story
          </AppButton>
          {loading && statusMessage ? (
            <Text style={styles.status} variant="bodySmall">
              {statusMessage}
            </Text>
          ) : null}

          <Divider style={styles.divider} />

          <Text variant="titleMedium" style={styles.label}>
            Or Browse Sources
          </Text>
          <Text variant="bodyMedium" style={styles.subtext}>
            Browse Royal Road or Scribble Hub directly and import novels from their web pages.
          </Text>

          <View style={styles.cardsContainer}>
            <SourceCard
              title="Royal Road"
              subtitle="royalroad.com"
              icon="compass-outline"
              accentColor="#D85A38"
              onPress={() => router.push("/browser?url=https://www.royalroad.com")}
            />
            <SourceCard
              title="Scribble Hub"
              subtitle="scribblehub.com"
              icon="book-open-variant"
              accentColor="#7B2CBF"
              onPress={() => router.push("/browser?url=https://www.scribblehub.com")}
            />
          </View>
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  screen: {
    paddingHorizontal: 8,
  },
  scrollContent: {
    paddingVertical: 16,
  },
  form: {
    width: "100%",
    alignSelf: "center",
  },
  label: {
    marginBottom: 8,
  },
  inputContainer: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 16,
  },

  input: {
    flex: 1,
    marginRight: 8,
  },
  pasteButton: {
    margin: 0,
  },
  button: {
    marginTop: 8,
    width: "100%",
  },
  status: {
    marginTop: 12,
    textAlign: "center",
    color: "#666",
  },
  divider: {
    marginVertical: 24,
  },
  subtext: {
    color: "#666",
    marginBottom: 16,
  },
  cardsContainer: {
    flexDirection: "row",
    gap: 12,
    justifyContent: "space-between",
  },
});
