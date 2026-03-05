import React from "react";
import { Portal, Dialog, TextInput, Button } from "react-native-paper";

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
          <Button onPress={onDismiss}>Cancel</Button>
          <Button onPress={onSave}>Save</Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}
