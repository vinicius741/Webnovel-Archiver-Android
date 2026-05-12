import React, { useState } from "react";
import { Dialog, Portal, TextInput } from "react-native-paper";
import { AppButton } from "../theme/AppButton";

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

  const handleSave = () => {
    if (name.trim()) {
      onSave(name.trim());
      onDismiss();
    }
  };

  const handleDismiss = () => {
    setName(initialValue);
    onDismiss();
  };

  return (
    <Portal>
      <Dialog
        key={`${visible}-${initialValue}`}
        visible={visible}
        onDismiss={handleDismiss}
      >
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
          <AppButton onPress={handleDismiss}>Cancel</AppButton>
          <AppButton onPress={handleSave} disabled={!name.trim()}>
            Save
          </AppButton>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
};
