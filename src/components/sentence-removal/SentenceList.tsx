import React from "react";
import { View } from "react-native";
import { List, IconButton, Divider, useTheme } from "react-native-paper";
import { ListSectionLayout, listItemStyles } from "./ListSectionLayout";

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
  const theme = useTheme();
  return (
    <ListSectionLayout
      title="Exact Sentence Removal"
      description="Removes exact text matches from downloaded chapters."
      emptyText="No sentences in the list."
      onAdd={onAdd}
      itemCount={sentences.length}
    >
      {sentences.map((item, index) => (
        <View key={`sentence-${index}`}>
          <List.Item
            title={item}
            titleNumberOfLines={3}
            right={() => (
              <View style={listItemStyles.rowActions}>
                <IconButton
                  icon="pencil"
                  onPress={() => onEdit(item, index)}
                />
                <IconButton
                  icon="delete"
                  iconColor={theme.colors.error}
                  onPress={() => onDelete(index)}
                />
              </View>
            )}
            style={listItemStyles.listItem}
          />
          <Divider />
        </View>
      ))}
    </ListSectionLayout>
  );
}
