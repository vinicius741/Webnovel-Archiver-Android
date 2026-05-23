import React from "react";
import { StyleSheet, View, ScrollView, TouchableOpacity } from "react-native";
import { Text, RadioButton, Divider } from "react-native-paper";

interface TabSelectionListProps {
  tabs: Array<{ id: string; name: string }>;
  selectedTabId: string | undefined;
  onSelectTab: (id: string | undefined) => void;
}

export const TabSelectionList: React.FC<TabSelectionListProps> = ({
  tabs,
  selectedTabId,
  onSelectTab,
}) => {
  const currentValue = selectedTabId === undefined ? "unassigned" : selectedTabId;

  return (
    <View style={styles.tabSection}>
      <Text variant="titleSmall" style={styles.tabLabelHeader}>
        Add to Tab
      </Text>
      <ScrollView style={styles.tabScrollContainer}>
        <TouchableOpacity
          style={styles.tabOption}
          onPress={() => onSelectTab(undefined)}
          accessibilityRole="radio"
          accessibilityState={{ selected: currentValue === "unassigned" }}
        >
          <RadioButton
            value="unassigned"
            status={currentValue === "unassigned" ? "checked" : "unchecked"}
            onPress={() => onSelectTab(undefined)}
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
              onPress={() => onSelectTab(tab.id)}
              accessibilityRole="radio"
              accessibilityState={{ selected: currentValue === tab.id }}
            >
              <RadioButton
                value={tab.id}
                status={currentValue === tab.id ? "checked" : "unchecked"}
                onPress={() => onSelectTab(tab.id)}
              />
              <Text variant="bodyLarge" style={styles.tabLabel}>
                {tab.name}
              </Text>
            </TouchableOpacity>
          </React.Fragment>
        ))}
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  tabSection: {
    marginTop: 8,
  },
  tabLabelHeader: {
    marginBottom: 8,
    fontWeight: "bold",
  },
  tabScrollContainer: {
    maxHeight: 200,
    borderWidth: 1,
    borderColor: "rgba(0, 0, 0, 0.08)",
    borderRadius: 8,
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
});
