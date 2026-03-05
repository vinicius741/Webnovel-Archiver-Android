import React from "react";
import { StyleSheet, View } from "react-native";
import {
  List,
  IconButton,
  Divider,
  Text,
  Button,
  Switch,
} from "react-native-paper";
import { RegexCleanupRule, RegexCleanupAppliesTo } from "../../types";
import { TARGET_LABEL_MAP } from "../../types/sentenceRemoval";

interface RegexRuleListProps {
  rules: RegexCleanupRule[];
  onAdd: () => void;
  onEdit: (rule: RegexCleanupRule) => void;
  onDelete: (ruleId: string) => void;
  onToggle: (ruleId: string, enabled: boolean) => void;
}

export function RegexRuleList({
  rules,
  onAdd,
  onEdit,
  onDelete,
  onToggle,
}: RegexRuleListProps) {
  return (
    <List.Section>
      <View style={styles.sectionHeader}>
        <List.Subheader>Regex Cleanup Rules</List.Subheader>
        <Button icon="plus" mode="text" onPress={onAdd}>
          Add
        </Button>
      </View>
      <Text variant="bodySmall" style={styles.sectionDescription}>
        Remove patterns like long separators. Rules run in guarded mode and skip
        invalid patterns.
      </Text>
      <Divider />
      {rules.length === 0 ? (
        <View style={styles.emptyContainer}>
          <Text variant="bodyMedium">No regex cleanup rules yet.</Text>
        </View>
      ) : (
        rules.map((rule) => (
          <View key={rule.id}>
            <List.Item
              title={rule.name}
              description={`/${rule.pattern}/${rule.flags} • ${TARGET_LABEL_MAP[rule.appliesTo]}`}
              right={() => (
                <View style={styles.rowActions}>
                  <Switch
                    value={rule.enabled}
                    onValueChange={(value) => onToggle(rule.id, value)}
                  />
                  <IconButton icon="pencil" onPress={() => onEdit(rule)} />
                  <IconButton
                    icon="delete"
                    iconColor="red"
                    onPress={() => onDelete(rule.id)}
                  />
                </View>
              )}
              style={[styles.listItem, !rule.enabled && styles.disabledItem]}
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
  disabledItem: {
    opacity: 0.5,
  },
});
