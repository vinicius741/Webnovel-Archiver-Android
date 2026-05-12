import React from "react";
import { SegmentedButtons, List } from "react-native-paper";
import { StyleSheet, View, ScrollView } from "react-native";
import { ScreenContainer } from "../src/components/ScreenContainer";
import { ThemePicker } from "../src/components/settings/ThemePicker";
import { useFoldLayoutMode } from "../src/context/FoldLayoutContext";
import { useSettings } from "../src/hooks/useSettings";
import { useScreenLayout } from "../src/hooks/useScreenLayout";
import { router } from "expo-router";

type AdaptiveLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
};

export default function SettingsScreen() {
  const screenLayout = useScreenLayout() as AdaptiveLayout;
  const { foldLayoutMode, setFoldLayoutMode } = useFoldLayoutMode();
  const widthClass =
    screenLayout.widthClass ??
    (screenLayout.screenWidth >= 840
      ? "expanded"
      : screenLayout.screenWidth >= 600
        ? "medium"
        : "compact");
  const {
    clearData,
    handleExportBackup,
    handleImportBackup,
  } = useSettings();
  const contentMaxWidth =
    widthClass === "expanded" ? 840 : widthClass === "medium" ? 720 : undefined;

  return (
    <ScreenContainer edges={["bottom", "left", "right"]} style={styles.screen}>
      <ScrollView contentContainerStyle={styles.scrollContent}>
        <View
          style={[
            styles.content,
            contentMaxWidth ? { maxWidth: contentMaxWidth } : undefined,
          ]}
        >
        <List.Section>
          <List.Subheader>Appearance</List.Subheader>
          <View style={styles.container}>
            <ThemePicker />
          </View>
          <View style={styles.container}>
            <SegmentedButtons
              value={foldLayoutMode}
              onValueChange={(value) => {
                if (value === "auto" || value === "cover" || value === "inner") {
                  void setFoldLayoutMode(value);
                }
              }}
              buttons={[
                {
                  value: "auto",
                  label: "Auto",
                  icon: "auto-fix",
                },
                {
                  value: "cover",
                  label: "Cover",
                  icon: "cellphone",
                },
                {
                  value: "inner",
                  label: "Inner",
                  icon: "tablet-cellphone",
                },
              ]}
            />
          </View>
        </List.Section>

        <List.Section>
          <List.Subheader>Downloads</List.Subheader>
          <View style={styles.container}>
            <List.Item
              title="Download Manager"
              description="View and manage active downloads"
              left={(props) => <List.Icon {...props} icon="download-circle" />}
              onPress={() => router.push("/download-manager")}
            />
          </View>
        </List.Section>

        <List.Section>
          <List.Subheader>Library Organization</List.Subheader>
          <View style={styles.container}>
            <List.Item
              title="Manage Tabs"
              description="Create and organize custom tabs for your library"
              left={(props) => <List.Icon {...props} icon="folder-multiple" />}
              onPress={() => router.push("/tab-management")}
            />
          </View>
        </List.Section>

        <List.Section>
          <List.Subheader>Data</List.Subheader>
          <View style={styles.container}>
            <List.Item
              title="Text Cleanup Rules"
              description="Manage sentence removal and regex cleanup rules"
              left={(props) => (
                <List.Icon {...props} icon="text-box-remove-outline" />
              )}
              onPress={() => router.push("/sentence-removal")}
            />
            <List.Item
              title="Clear Local Storage"
              description="Delete all novels and reset app data"
              left={(props) => <List.Icon {...props} icon="delete-outline" />}
              onPress={clearData}
            />
          </View>
        </List.Section>

        <List.Section>
          <List.Subheader>Backup</List.Subheader>
          <View style={styles.container}>
            <List.Item
              title="Export Backup"
              description="Export your library to a JSON file"
              left={(props) => <List.Icon {...props} icon="export-variant" />}
              onPress={handleExportBackup}
            />
            <List.Item
              title="Import Backup"
              description="Merge library from a backup file"
              left={(props) => <List.Icon {...props} icon="import" />}
              onPress={handleImportBackup}
            />
          </View>
        </List.Section>
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
    paddingBottom: 80,
    paddingTop: 8,
  },
  content: {
    width: "100%",
    alignSelf: "center",
  },
  container: {
    paddingHorizontal: 16,
    paddingBottom: 8,
  },
});
