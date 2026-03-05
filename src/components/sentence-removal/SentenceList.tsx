import React from "react";
import { StyleSheet, View } from "react-native";
import { List, IconButton, Divider, Text, Button } from "react-native-paper";

interface SentenceListProps {
  sentences: string[];
  onAdd: () => void;
  onEdit: (sentence: string, index: number) => void;
  onDelete: (index: number) => void;
}

export function SentenceList({
  sentences,
  onAdd,
  onEdit,
  onDelete,
}: SentenceListProps) {
  return (
    <List.Section>
      <View style={styles.sectionHeader}>
        <List.Subheader>Exact Sentence Removal</List.Subheader>
        <Button icon="plus" mode="text" onPress={onAdd}>
          Add
        </Button>
      </View>
      <Text variant="bodySmall" style={styles.sectionDescription}>
        Removes exact text matches from downloaded chapters.
      </Text>
      <Divider />
      {sentences.length === 0 ? (
        <View style={styles.emptyContainer}>
          <Text variant="bodyMedium">No sentences in the list.</Text>
        </View>
      ) : (
        sentences.map((item, index) => (
          <View key={`sentence-${index}`}>
            <List.Item
              title={item}
              titleNumberOfLines={3}
              right={() => (
                <View style={styles.rowActions}>
                  <IconButton
                    icon="pencil"
                    onPress={() => onEdit(item, index)}
                  />
                  <IconButton
                    icon="delete"
                    iconColor="red"
                    onPress={() => onDelete(index)}
                  />
                </View>
              )}
              style={styles.listItem}
            />
            <Divider />
          </View>
        ))
      )}
    </List.Section>
  );
}

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
  listItem: {
    paddingVertical: 4,
  },
  rowActions: {
    flexDirection: "row",
    alignItems: "center",
  },
  emptyContainer: {
    paddingHorizontal: 16,
    paddingVertical: 20,
  },
});
