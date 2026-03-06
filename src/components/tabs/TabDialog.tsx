import React, { useState, useEffect } from "react";
import { Dialog, Portal, TextInput, Button } from "react-native-paper";

interface TabDialogProps {
  visible: boolean;
  onDismiss: () => void;
  onSave: (name: string) => void;
  initialValue?: string;
  title?: string;
}

export const TabDialog = ({
  visible,
  onDismiss,
  onSave,
  initialValue = "",
  title = "New Tab",
}: TabDialogProps) => {
  const [name, setName] = useState(initialValue);

  useEffect(() => {
    if (visible) {
      setName(initialValue);
    }
  }, [visible, initialValue]);

  const handleSave = () => {
    if (name.trim()) {
      onSave(name.trim());
      onDismiss();
    }
  };

  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss}>
        <Dialog.Title>{title}</Dialog.Title>
        <Dialog.Content>
          <TextInput
            label="Tab Name"
            value={name}
            onChangeText={setName}
            mode="outlined"
            autoFocus
            maxLength={50}
            onSubmitEditing={handleSave}
          />
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss}>Cancel</Button>
          <Button onPress={handleSave} disabled={!name.trim()}>
            Save
          </Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
};
