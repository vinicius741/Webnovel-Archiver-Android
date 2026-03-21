import React, { useState, useCallback } from "react";
import { StyleSheet, View, TouchableOpacity } from "react-native";
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
import { useTabs } from "../src/hooks/useTabs";

export default function AddStoryScreen() {
  const theme = useTheme();
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

  return (
    <ScreenContainer edges={["bottom", "left", "right"]} style={{ padding: 8 }}>
      <View style={styles.form}>
        <Text variant="titleMedium" style={styles.label}>
          Webnovel URL
        </Text>
        <View style={styles.inputContainer}>
          <TextInput
            mode="outlined"
            placeholder="https://www.royalroad.com/fiction/..."
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
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  form: {
    paddingTop: 16,
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
  },
  status: {
    marginTop: 12,
    textAlign: "center",
    color: "#666",
  },
});
