import React from "react";
import { StyleSheet, View } from "react-native";
import { List, Divider, Text, Button } from "react-native-paper";

interface ListSectionLayoutProps {
  title: string;
  description: string;
  emptyText: string;
  onAdd: () => void;
  itemCount: number;
  children: React.ReactNode;
}

export function ListSectionLayout({
  title,
  description,
  emptyText,
  onAdd,
  itemCount,
  children,
}: ListSectionLayoutProps) {
  return (
    <List.Section>
      <View style={styles.sectionHeader}>
        <List.Subheader>{title}</List.Subheader>
        <Button icon="plus" mode="text" onPress={onAdd}>
          Add
        </Button>
      </View>
      <Text variant="bodySmall" style={styles.sectionDescription}>
        {description}
      </Text>
      <Divider />
      {itemCount === 0 ? (
        <View style={styles.emptyContainer}>
          <Text variant="bodyMedium">{emptyText}</Text>
        </View>
      ) : (
        children
      )}
    </List.Section>
  );
}

export const listItemStyles = StyleSheet.create({
  listItem: {
    paddingVertical: 4,
  },
  rowActions: {
    flexDirection: "row",
    alignItems: "center",
  },
  disabledItem: {
    opacity: 0.5,
  },
});

const styles = StyleSheet.create({
  sectionHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  sectionDescription: {
    paddingHorizontal: 16,
    marginBottom: 8,
    opacity: 0.75,
  },
  emptyContainer: {
    paddingHorizontal: 16,
    paddingVertical: 20,
  },
});
