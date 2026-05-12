import React, { useState, useCallback } from "react";
import {
  StyleSheet,
  View,
  FlatList,
  Alert,
  Pressable,
} from "react-native";
import {
  Text,
  IconButton,
  FAB,
  useTheme,
  Divider,
} from "react-native-paper";
import { Stack } from "expo-router";
import { ScreenContainer } from "../src/components/ScreenContainer";
import { useTabManagement } from "../src/hooks/useTabManagement";
import { useScreenLayout } from "../src/hooks/useScreenLayout";
import { TabDialog } from "../src/components/tabs/TabDialog";
import { Tab } from "../src/types/tab";

export default function TabManagementScreen() {
  const theme = useTheme();
  const { widthClass } = useScreenLayout();
  const {
    tabs,
    loading,
    storyCountByTab,
    unassignedCount,
    addTab,
    updateTab,
    deleteTab,
    moveTabUp,
    moveTabDown,
  } = useTabManagement();

  const [dialogVisible, setDialogVisible] = useState(false);
  const [editingTab, setEditingTab] = useState<Tab | null>(null);
  const contentMaxWidth = widthClass === "expanded" ? 840 : widthClass === "medium" ? 720 : undefined;

  const handleAddTab = useCallback(
    async (name: string) => {
      await addTab(name);
    },
    [addTab],
  );

  const handleEditTab = useCallback(
    async (name: string) => {
      if (editingTab) {
        await updateTab(editingTab.id, name);
        setEditingTab(null);
      }
    },
    [editingTab, updateTab],
  );

  const handleDeleteTab = useCallback(
    (tab: Tab) => {
      const count = storyCountByTab[tab.id] || 0;
      const message =
        count > 0
          ? `This tab contains ${count} novel${count > 1 ? "s" : ""}. They will be moved to Unassigned.`
          : "This tab is empty.";

      Alert.alert(
        "Delete Tab",
        `Are you sure you want to delete "${tab.name}"?\n\n${message}`,
        [
          { text: "Cancel", style: "cancel" },
          {
            text: "Delete",
            style: "destructive",
            onPress: async () => {
              const deletedCount = await deleteTab(tab.id);
              if (deletedCount > 0) {
                Alert.alert(
                  "Tab Deleted",
                  `${deletedCount} novel${deletedCount > 1 ? "s" : ""} moved to Unassigned.`,
                );
              }
            },
          },
        ],
      );
    },
    [deleteTab, storyCountByTab],
  );

  const openEditDialog = (tab: Tab) => {
    setEditingTab(tab);
    setDialogVisible(true);
  };

  const openAddDialog = () => {
    setEditingTab(null);
    setDialogVisible(true);
  };

  const closeDialog = () => {
    setDialogVisible(false);
    setEditingTab(null);
  };

  const renderTabItem = ({ item, index }: { item: Tab; index: number }) => {
    const count = storyCountByTab[item.id] || 0;

    return (
      <Pressable
        onLongPress={() => openEditDialog(item)}
        style={({ pressed }) => [
          styles.tabItem,
          pressed && { backgroundColor: theme.colors.surfaceVariant },
        ]}
      >
        <View style={styles.tabContent}>
          <View style={styles.reorderButtons}>
            <IconButton
              icon="chevron-up"
              size={20}
              disabled={index === 0}
              onPress={() => moveTabUp(index)}
              style={styles.reorderButton}
            />
            <IconButton
              icon="chevron-down"
              size={20}
              disabled={index === tabs.length - 1}
              onPress={() => moveTabDown(index)}
              style={styles.reorderButton}
            />
          </View>
          <View style={styles.tabInfo}>
            <Text variant="bodyLarge" style={styles.tabName}>
              {item.name}
            </Text>
            <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
              {count} novel{count !== 1 ? "s" : ""}
            </Text>
          </View>
          <View style={styles.tabActions}>
            <IconButton
              icon="pencil"
              size={20}
              onPress={() => openEditDialog(item)}
            />
            <IconButton
              icon="delete"
              size={20}
              iconColor={theme.colors.error}
              onPress={() => handleDeleteTab(item)}
            />
          </View>
        </View>
      </Pressable>
    );
  };

  return (
    <ScreenContainer edges={["bottom", "left", "right"]}>
      <Stack.Screen
        options={{
          title: "Manage Tabs",
          headerBackTitle: "Settings",
        }}
      />

      <View style={styles.container}>
        <View
          style={[
            styles.content,
            contentMaxWidth ? { maxWidth: contentMaxWidth } : undefined,
          ]}
        >
        {tabs.length === 0 && !loading ? (
          <View style={styles.emptyState}>
            <IconButton icon="folder-outline" size={64} iconColor={theme.colors.outline} />
            <Text variant="titleMedium" style={styles.emptyTitle}>
              No Custom Tabs
            </Text>
            <Text variant="bodyMedium" style={styles.emptyDescription}>
              Create tabs to organize your library.{"\n"}
              Novels can be moved between tabs via{"\n"}
              long-press on the library screen.
            </Text>
          </View>
        ) : (
          <FlatList
            data={tabs}
            keyExtractor={(item) => item.id}
            renderItem={renderTabItem}
            ItemSeparatorComponent={() => <Divider />}
            contentContainerStyle={styles.listContent}
            ListHeaderComponent={
              unassignedCount > 0 ? (
                <View style={styles.unassignedInfo}>
                  <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
                    {unassignedCount} unassigned novel{unassignedCount !== 1 ? "s" : ""} (visible in Unassigned tab)
                  </Text>
                </View>
              ) : null
            }
          />
        )}
        </View>

        <FAB
          icon="plus"
          label="New Tab"
          style={[styles.fab, { backgroundColor: theme.colors.primary }]}
          onPress={openAddDialog}
          color={theme.colors.onPrimary}
        />
      </View>

      <TabDialog
        visible={dialogVisible}
        onDismiss={closeDialog}
        onSave={editingTab ? handleEditTab : handleAddTab}
        initialValue={editingTab?.name || ""}
        title={editingTab ? "Edit Tab" : "New Tab"}
      />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingHorizontal: 8,
    paddingTop: 8,
  },
  content: {
    flex: 1,
    width: "100%",
    alignSelf: "center",
  },
  listContent: {
    paddingBottom: 100,
  },
  tabItem: {
    minHeight: 56,
  },
  tabContent: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 8,
    flex: 1,
  },
  reorderButtons: {
    flexDirection: "column",
    marginHorizontal: -8,
  },
  reorderButton: {
    margin: -8,
  },
  tabInfo: {
    flex: 1,
    marginLeft: 8,
  },
  tabName: {
  },
  tabActions: {
    flexDirection: "row",
  },
  unassignedInfo: {
    padding: 16,
    alignItems: "center",
  },
  emptyState: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    paddingHorizontal: 32,
  },
  emptyTitle: {
    marginTop: 16,
    marginBottom: 8,
  },
  emptyDescription: {
    textAlign: "center",
    opacity: 0.7,
  },
  fab: {
    position: "absolute",
    margin: 16,
    right: 0,
    bottom: 0,
  },
});
