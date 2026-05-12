import React from "react";
import { ScrollView, StyleSheet, TouchableOpacity } from "react-native";
import {
  Dialog,
  Portal,
  Text,
  RadioButton,
} from "react-native-paper";
import { AppButton } from "../theme/AppButton";
import { Tab } from "../../types/tab";

interface MoveToTabDialogProps {
  visible: boolean;
  onDismiss: () => void;
  tabs: Tab[];
  selectedCount: number;
  onMove: (tabId: string | null) => void;
}

export const MoveToTabDialog = ({
  visible,
  onDismiss,
  tabs,
  selectedCount,
  onMove,
}: MoveToTabDialogProps) => {
  const [selectedTabId, setSelectedTabId] = React.useState<string | null>(null);

  // Reset selection when dialog opens
  React.useEffect(() => {
    if (visible) {
      setSelectedTabId(null);
    }
  }, [visible]);

  const handleMove = () => {
    onMove(selectedTabId);
  };

  const handleSelect = (value: string) => {
    setSelectedTabId(value === "unassigned" ? null : value);
  };

  const currentValue = selectedTabId === null ? "unassigned" : selectedTabId;

  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss}>
        <Dialog.Title>Move to Tab</Dialog.Title>
        <Dialog.Content>
          <Text variant="bodyMedium" style={styles.countText}>
            {selectedCount} novel{selectedCount !== 1 ? "s" : ""} selected
          </Text>
          <ScrollView style={styles.list}>
            <TouchableOpacity
              style={styles.option}
              onPress={() => handleSelect("unassigned")}
            >
              <RadioButton
                value="unassigned"
                status={currentValue === "unassigned" ? "checked" : "unchecked"}
                onPress={() => handleSelect("unassigned")}
              />
              <Text variant="bodyLarge" style={styles.optionLabel}>
                Unassigned
              </Text>
            </TouchableOpacity>
            {tabs.map((tab) => (
              <TouchableOpacity
                key={tab.id}
                style={styles.option}
                onPress={() => handleSelect(tab.id)}
              >
                <RadioButton
                  value={tab.id}
                  status={currentValue === tab.id ? "checked" : "unchecked"}
                  onPress={() => handleSelect(tab.id)}
                />
                <Text variant="bodyLarge" style={styles.optionLabel}>
                  {tab.name}
                </Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
        </Dialog.Content>
        <Dialog.Actions>
          <AppButton onPress={onDismiss}>Cancel</AppButton>
          <AppButton onPress={handleMove}>Move</AppButton>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
};

const styles = StyleSheet.create({
  countText: {
    marginBottom: 16,
  },
  list: {
    maxHeight: 300,
  },
  option: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 16,
    borderBottomWidth: 1,
    borderBottomColor: "rgba(128,128,128,0.3)",
  },
  optionLabel: {
    marginLeft: 12,
  },
});
