import React from "react";
import { StyleSheet, View, ScrollView, Pressable } from "react-native";
import { Text, useTheme } from "react-native-paper";
import { Tab } from "../../types/tab";

interface LibraryTabBarProps {
  tabs: Tab[];
  activeTabId: string | null;
  showUnassignedTab: boolean;
  unassignedCount: number;
  onSelectTab: (tabId: string | null) => void;
}

export const LibraryTabBar = ({
  tabs,
  activeTabId,
  showUnassignedTab,
  unassignedCount,
  onSelectTab,
}: LibraryTabBarProps) => {
  const theme = useTheme();

  return (
    <View style={styles.container}>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.scrollContent}
      >
        {tabs.map((tab) => (
          <Pressable
            key={tab.id}
            onPress={() => onSelectTab(tab.id)}
            style={styles.tabWrapper}
          >
            <View
              style={[
                styles.tab,
                activeTabId === tab.id && {
                  borderBottomColor: theme.colors.primary,
                  borderBottomWidth: 2,
                },
                activeTabId !== tab.id && {
                  borderBottomColor: theme.colors.surfaceVariant,
                  borderBottomWidth: 2,
                },
              ]}
            >
              <Text
                variant="labelLarge"
                style={[
                  styles.tabText,
                  {
                    color:
                      activeTabId === tab.id
                        ? theme.colors.primary
                        : theme.colors.onSurfaceVariant,
                  },
                ]}
              >
                {tab.name}
              </Text>
            </View>
          </Pressable>
        ))}
        {showUnassignedTab && (
          <Pressable
            onPress={() => onSelectTab("unassigned")}
            style={styles.tabWrapper}
          >
            <View
              style={[
                styles.tab,
                activeTabId === "unassigned" && {
                  borderBottomColor: theme.colors.primary,
                  borderBottomWidth: 2,
                },
                activeTabId !== "unassigned" && {
                  borderBottomColor: theme.colors.surfaceVariant,
                  borderBottomWidth: 2,
                },
              ]}
            >
              <Text
                variant="labelLarge"
                style={[
                  styles.tabText,
                  {
                    color:
                      activeTabId === "unassigned"
                        ? theme.colors.primary
                        : theme.colors.onSurfaceVariant,
                  },
                ]}
              >
                Unassigned ({unassignedCount})
              </Text>
            </View>
          </Pressable>
        )}
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    marginTop: 0,
    marginBottom: 16,
    backgroundColor: "transparent",
  },
  scrollContent: {
    gap: 0,
    paddingHorizontal: 0,
  },
  tabWrapper: {
    marginRight: 16,
  },
  tab: {
    paddingVertical: 10,
    paddingHorizontal: 4,
    minWidth: 60,
    alignItems: "center",
    justifyContent: "center",
  },
  tabText: {
    fontWeight: "600",
  },
});
