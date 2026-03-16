import React, { useState } from "react";
import { View, StyleSheet } from "react-native";
import {
  Button,
  Dialog,
  Portal,
  TextInput,
  Text,
  HelperText,
} from "react-native-paper";

interface DownloadRangeDialogProps {
  visible: boolean;
  onDismiss: () => void;
  onDownload: (start: number, end: number) => void;
  totalChapters: number;
}

const getInitialRangeState = (totalChapters: number) => ({
  start: "1",
  end: totalChapters.toString(),
  error: "",
});

interface DownloadRangeDialogContentProps {
  onDismiss: () => void;
  onDownload: (start: number, end: number) => void;
  totalChapters: number;
}

const DownloadRangeDialogContent: React.FC<DownloadRangeDialogContentProps> = ({
  onDismiss,
  onDownload,
  totalChapters,
}) => {
  const initialState = getInitialRangeState(totalChapters);
  const [start, setStart] = useState(initialState.start);
  const [end, setEnd] = useState(initialState.end);
  const [error, setError] = useState(initialState.error);

  const handleDownload = () => {
    const startNum = parseInt(start, 10);
    const endNum = parseInt(end, 10);

    if (isNaN(startNum) || isNaN(endNum)) {
      setError("Please enter valid numbers");
      return;
    }

    if (startNum < 1 || endNum > totalChapters) {
      setError(`Range must be between 1 and ${totalChapters}`);
      return;
    }

    if (startNum > endNum) {
      setError("Start chapter cannot be greater than end chapter");
      return;
    }

    setError("");
    onDownload(startNum, endNum);
    onDismiss();
  };

  return (
    <Portal>
      <Dialog visible onDismiss={onDismiss}>
        <Dialog.Title>Download Range</Dialog.Title>
        <Dialog.Content>
          <Text variant="bodyMedium" style={{ marginBottom: 16 }}>
            Total Chapters: {totalChapters}
          </Text>
          <View style={styles.inputContainer}>
            <TextInput
              label="From"
              value={start}
              onChangeText={setStart}
              keyboardType="number-pad"
              style={styles.input}
              mode="outlined"
            />
            <Text style={styles.separator}>-</Text>
            <TextInput
              label="To"
              value={end}
              onChangeText={setEnd}
              keyboardType="number-pad"
              style={styles.input}
              mode="outlined"
            />
          </View>
          {error ? (
            <HelperText type="error" visible={!!error}>
              {error}
            </HelperText>
          ) : null}
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss}>Cancel</Button>
          <Button onPress={handleDownload}>Download</Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
};

export const DownloadRangeDialog: React.FC<DownloadRangeDialogProps> = ({
  visible,
  onDismiss,
  onDownload,
  totalChapters,
}) => {
  if (!visible) return null;
  return (
    <DownloadRangeDialogContent
      key={`download-range-${totalChapters}`}
      onDismiss={onDismiss}
      onDownload={onDownload}
      totalChapters={totalChapters}
    />
  );
};

const styles = StyleSheet.create({
  inputContainer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  input: {
    flex: 1,
  },
  separator: {
    marginHorizontal: 16,
    fontSize: 20,
  },
});
