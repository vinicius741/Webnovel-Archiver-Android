import React, { useState, useCallback } from "react";
import { StyleSheet, View, TouchableOpacity, ScrollView } from "react-native";
import {
  TextInput,
  Button,
  Text,
  IconButton,
  useTheme,
  RadioButton,
  Divider,
} from "react-native-paper";
import { ScreenContainer } from "../src/components/ScreenContainer";
import { useAddStory } from "../src/hooks/useAddStory";
import { useScreenLayout } from "../src/hooks/useScreenLayout";
import { useTabs } from "../src/hooks/useTabs";

type AdaptiveLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
};

export default function AddStoryScreen() {
  const theme = useTheme();
  const screenLayout = useScreenLayout() as AdaptiveLayout;
  const widthClass =
    screenLayout.widthClass ??
    (screenLayout.screenWidth >= 840
      ? "expanded"
      : screenLayout.screenWidth >= 600
        ? "medium"
        : "compact");
  const isCompact = widthClass === "compact";
  const { url, setUrl, loading, statusMessage, handlePaste, handleAdd } =
    useAddStory();
  const { tabs, hasCustomTabs } = useTabs();
  const [selectedTabId, setSelectedTabId] = useState<string | undefined>(undefined);

  const onAdd = useCallback(() => {
    void handleAdd(selectedTabId);
  }, [handleAdd, selectedTabId]);

  const handleSelectTab = (value: string) => {
    setSelectedTabId(value === "unassigned" ? undefined : value);
  };

  const currentValue = selectedTabId === undefined ? "unassigned" : selectedTabId;
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
          <View
            style={[
              styles.inputContainer,
              isCompact && styles.inputContainerCompact,
            ]}
          >
            <TextInput
              mode="outlined"
              placeholder="Royal Road or Scribble Hub story URL"
              value={url}
              onChangeText={setUrl}
              autoCapitalize="none"
              keyboardType="url"
              style={[styles.input, isCompact && styles.inputCompact]}
            />
            <IconButton
              icon="content-paste"
              mode="contained-tonal"
              onPress={handlePaste}
              style={[styles.pasteButton, isCompact && styles.pasteButtonCompact]}
            />
          </View>

          {hasCustomTabs && (
            <View style={styles.tabSection}>
              <Text variant="titleMedium" style={styles.label}>
                Add to Tab
              </Text>
              <View style={[styles.tabList, { borderColor: theme.colors.outlineVariant }]}>
                <TouchableOpacity
                  style={styles.tabOption}
                  onPress={() => handleSelectTab("unassigned")}
                  accessibilityRole="radio"
                  accessibilityState={{ selected: currentValue === "unassigned" }}
                >
                  <RadioButton
                    value="unassigned"
                    status={currentValue === "unassigned" ? "checked" : "unchecked"}
                  />
                  <Text variant="bodyLarge" style={styles.tabLabel}>
                    Unassigned
                  </Text>
                </TouchableOpacity>
                {tabs.map((tab) => (
                  <React.Fragment key={tab.id}>
                    <Divider />
                    <TouchableOpacity
                      style={styles.tabOption}
                      onPress={() => handleSelectTab(tab.id)}
                      accessibilityRole="radio"
                      accessibilityState={{ selected: currentValue === tab.id }}
                    >
                      <RadioButton
                        value={tab.id}
                        status={currentValue === tab.id ? "checked" : "unchecked"}
                      />
                      <Text variant="bodyLarge" style={styles.tabLabel}>
                        {tab.name}
                      </Text>
                    </TouchableOpacity>
                  </React.Fragment>
                ))}
              </View>
            </View>
          )}

          <Button
            mode="contained"
            onPress={onAdd}
            loading={loading}
            disabled={loading || !url}
            style={styles.button}
          >
            Fetch Story
          </Button>
          {loading && statusMessage ? (
            <Text style={styles.status} variant="bodySmall">
              {statusMessage}
            </Text>
          ) : null}
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
  inputContainerCompact: {
    flexDirection: "column",
    alignItems: "stretch",
  },
  input: {
    flex: 1,
    marginRight: 8,
  },
  inputCompact: {
    width: "100%",
    marginRight: 0,
    marginBottom: 8,
  },
  pasteButton: {
    margin: 0,
  },
  pasteButtonCompact: {
    alignSelf: "flex-end",
  },
  tabSection: {
    marginBottom: 16,
  },
  tabList: {
    borderWidth: 1,
    borderRadius: 8,
    overflow: "hidden",
  },
  tabOption: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 8,
    paddingHorizontal: 4,
  },
  tabLabel: {
    marginLeft: 8,
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
});
