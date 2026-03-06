import React from "react";
import { ScrollView, StyleSheet, View } from "react-native";
import {
  Dialog,
  Portal,
  Text,
  RadioButton,
  Button,
  useTheme,
} from "react-native-paper";
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
  const theme = useTheme();
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

  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss}>
        <Dialog.Title>Move to Tab</Dialog.Title>
        <Dialog.Content>
          <Text variant="bodyMedium" style={styles.countText}>
            {selectedCount} novel{selectedCount !== 1 ? "s" : ""} selected
          </Text>
          <ScrollView style={styles.list}>
            <RadioButton.Group
              onValueChange={(value) => setSelectedTabId(value === "unassigned" ? null : value)}
              value={selectedTabId === null ? "unassigned" : selectedTabId}
            >
              <View style={styles.item}>
                <RadioButton.Item
                  label="Unassigned"
                  value="unassigned"
                  position="leading"
                />
              </View>
              {tabs.map((tab) => (
                <View key={tab.id} style={styles.item}>
                  <RadioButton.Item
                    label={tab.name}
                    value={tab.id}
                    position="leading"
                  />
                </View>
              ))}
            </RadioButton.Group>
          </ScrollView>
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss}>Cancel</Button>
          <Button onPress={handleMove}>Move</Button>
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
  item: {
    borderBottomWidth: 1,
    borderBottomColor: "rgba(0,0,0,0.1)",
  },
});
