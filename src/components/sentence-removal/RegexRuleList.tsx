import React from "react";
import { View } from "react-native";
import { List, IconButton, Divider, Switch, useTheme } from "react-native-paper";
import { RegexCleanupRule } from "../../types";
import { TARGET_LABEL_MAP } from "../../types/sentenceRemoval";
import { ListSectionLayout, listItemStyles } from "./ListSectionLayout";

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
  const theme = useTheme();
  return (
    <ListSectionLayout
      title="Regex Cleanup Rules"
      description="Remove patterns like long separators. Rules run in guarded mode and skip invalid patterns."
      emptyText="No regex cleanup rules yet."
      onAdd={onAdd}
      itemCount={rules.length}
    >
      {rules.map((rule) => (
        <View key={rule.id}>
          <List.Item
            title={rule.name}
            description={`/${rule.pattern}/${rule.flags} • ${TARGET_LABEL_MAP[rule.appliesTo]}`}
            right={() => (
              <View style={listItemStyles.rowActions}>
                <Switch
                  value={rule.enabled}
                  onValueChange={(value) => onToggle(rule.id, value)}
                />
                <IconButton icon="pencil" onPress={() => onEdit(rule)} />
                <IconButton
                  icon="delete"
                  iconColor={theme.colors.error}
                  onPress={() => onDelete(rule.id)}
                />
              </View>
            )}
            style={[
              listItemStyles.listItem,
              !rule.enabled && listItemStyles.disabledItem,
            ]}
          />
          <Divider />
        </View>
      ))}
    </ListSectionLayout>
  );
}
