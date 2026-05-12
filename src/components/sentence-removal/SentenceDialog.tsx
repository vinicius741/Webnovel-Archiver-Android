import React from "react";
import { Portal, Dialog, TextInput } from "react-native-paper";
import { AppButton } from "../theme/AppButton";

interface SentenceDialogProps {
  visible: boolean;
  sentence: string;
  isEditing: boolean;
  onSentenceChange: (value: string) => void;
  onSave: () => void;
  onDismiss: () => void;
}

export function SentenceDialog({
  visible,
  sentence,
  isEditing,
  onSentenceChange,
  onSave,
  onDismiss,
}: SentenceDialogProps) {
  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss}>
        <Dialog.Title>
          {isEditing ? "Edit Sentence" : "Add Sentence"}
        </Dialog.Title>
        <Dialog.Content>
          <TextInput
            label="Sentence to remove"
            value={sentence}
            onChangeText={onSentenceChange}
            mode="outlined"
            multiline
            numberOfLines={3}
            autoFocus
          />
        </Dialog.Content>
        <Dialog.Actions>
          <AppButton onPress={onDismiss}>Cancel</AppButton>
          <AppButton onPress={onSave}>Save</AppButton>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}
