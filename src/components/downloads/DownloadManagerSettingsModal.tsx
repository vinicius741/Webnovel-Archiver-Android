import React from "react";
import { StyleSheet } from "react-native";
import {
  Text,
  useTheme,
  Modal,
  Button,
  TextInput,
  Divider,
} from "react-native-paper";

interface Props {
  visible: boolean;
  onDismiss: () => void;
  concurrency: string;
  concurrencyError: string | undefined;
  delay: string;
  delayError: string | undefined;
  onConcurrencyChange: (text: string) => void;
  onDelayChange: (text: string) => void;
  onConcurrencyBlur: () => void;
  onDelayBlur: () => void;
  onClearCompletedPress: () => void;
}

export const DownloadManagerSettingsModal: React.FC<Props> = ({
  visible,
  onDismiss,
  concurrency,
  concurrencyError,
  delay,
  delayError,
  onConcurrencyChange,
  onDelayChange,
  onConcurrencyBlur,
  onDelayBlur,
  onClearCompletedPress,
}) => {
  const theme = useTheme();

  return (
    <Modal
      visible={visible}
      onDismiss={onDismiss}
      contentContainerStyle={[
        styles.modalContent,
        { backgroundColor: theme.colors.surface },
      ]}
    >
      <Text variant="headlineSmall" style={styles.modalTitle}>
        Download Manager
      </Text>

      <Button
        mode="outlined"
        icon="delete-sweep"
        onPress={onClearCompletedPress}
        textColor={theme.colors.error}
        style={styles.clearButton}
      >
        Clear Completed Downloads
      </Button>

      <Divider style={styles.divider} />

      <TextInput
        label="Simultaneous Downloads"
        value={concurrency}
        onChangeText={onConcurrencyChange}
        onEndEditing={onConcurrencyBlur}
        keyboardType="number-pad"
        mode="outlined"
        style={styles.input}
        error={!!concurrencyError}
        right={<TextInput.Affix text="files" />}
      />
      {concurrencyError ? (
        <Text
          variant="bodySmall"
          style={[styles.error, { color: theme.colors.error }]}
        >
          {concurrencyError}
        </Text>
      ) : null}

      <TextInput
        label="Delay Between Downloads"
        value={delay}
        onChangeText={onDelayChange}
        onEndEditing={onDelayBlur}
        keyboardType="number-pad"
        mode="outlined"
        style={styles.input}
        error={!!delayError}
        right={<TextInput.Affix text="ms" />}
      />
      {delayError ? (
        <Text
          variant="bodySmall"
          style={[styles.error, { color: theme.colors.error }]}
        >
          {delayError}
        </Text>
      ) : null}

      <Button mode="contained" onPress={onDismiss} style={styles.closeButton}>
        Done
      </Button>
    </Modal>
  );
};

const styles = StyleSheet.create({
  modalContent: {
    margin: 20,
    padding: 20,
    borderRadius: 12,
  },
  modalTitle: {
    marginBottom: 16,
    fontWeight: "bold",
  },
  clearButton: {
    marginBottom: 8,
  },
  divider: {
    marginVertical: 12,
  },
  input: {
    marginBottom: 12,
  },
  error: {
    marginTop: -8,
    marginBottom: 8,
  },
  closeButton: {
    marginTop: 16,
  },
});
