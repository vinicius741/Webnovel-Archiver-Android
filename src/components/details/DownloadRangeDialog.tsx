import React, { useState } from "react";
import { StyleSheet, View } from "react-native";
import {
  Button,
  Dialog,
  HelperText,
  Portal,
  SegmentedButtons,
  Text,
  TextInput,
} from "react-native-paper";

import { DOWNLOAD_DEFAULT_CHAPTER_COUNT } from "../../constants/epub";
import type { DownloadRangeMode } from "../../types";

interface DownloadRangeDialogProps {
  visible: boolean;
  onDismiss: () => void;
  onDownload: (start: number, end: number) => void;
  totalChapters: number;
  hasBookmark: boolean;
  bookmarkChapterNumber?: number;
}

interface DownloadRangeDialogContentProps {
  onDismiss: () => void;
  onDownload: (start: number, end: number) => void;
  totalChapters: number;
  hasBookmark: boolean;
  bookmarkChapterNumber?: number;
}

const computePreview = (
  mode: DownloadRangeMode,
  rangeStart: string,
  rangeEnd: string,
  count: string,
  totalChapters: number,
  bookmarkChapterNumber?: number,
): string => {
  if (mode === "range") {
    const s = parseInt(rangeStart, 10);
    const e = parseInt(rangeEnd, 10);
    if (isNaN(s) || isNaN(e) || s < 1 || e > totalChapters || s > e)
      return "";
    return `Will download chapters ${s}\u2013${e}`;
  }

  if (mode === "bookmark") {
    if (!bookmarkChapterNumber) return "";
    const start = bookmarkChapterNumber + 1;
    if (start > totalChapters) return "Bookmark is at the last chapter";
    const c = parseInt(count, 10);
    if (isNaN(c) || c < 1) return "";
    const end = Math.min(start + c - 1, totalChapters);
    return `Will download chapters ${start}\u2013${end}`;
  }

  const s = parseInt(rangeStart, 10);
  const c = parseInt(count, 10);
  if (isNaN(s) || isNaN(c) || s < 1 || s > totalChapters || c < 1) return "";
  const end = Math.min(s + c - 1, totalChapters);
  return `Will download chapters ${s}\u2013${end}`;
};

const DownloadRangeDialogContent: React.FC<DownloadRangeDialogContentProps> = ({
  onDismiss,
  onDownload,
  totalChapters,
  hasBookmark,
  bookmarkChapterNumber,
}) => {
  const [mode, setMode] = useState<DownloadRangeMode>("range");
  const [rangeStart, setRangeStart] = useState("1");
  const [rangeEnd, setRangeEnd] = useState(totalChapters.toString());
  const [count, setCount] = useState(
    DOWNLOAD_DEFAULT_CHAPTER_COUNT.toString(),
  );
  const [countStart, setCountStart] = useState("1");
  const [error, setError] = useState("");

  const handleModeChange = (newMode: string) => {
    setError("");
    setMode(newMode as DownloadRangeMode);
  };

  const handleDownload = () => {
    if (totalChapters <= 0) {
      setError("No chapters available.");
      return;
    }

    if (mode === "range") {
      const startNum = parseInt(rangeStart, 10);
      const endNum = parseInt(rangeEnd, 10);

      if (isNaN(startNum) || isNaN(endNum)) {
        setError("Please enter valid numbers.");
        return;
      }
      if (startNum < 1 || endNum > totalChapters) {
        setError(`Range must be between 1 and ${totalChapters}.`);
        return;
      }
      if (startNum > endNum) {
        setError("Start chapter cannot be greater than end chapter.");
        return;
      }

      setError("");
      onDownload(startNum, endNum);
      onDismiss();
      return;
    }

    if (mode === "bookmark") {
      if (!bookmarkChapterNumber) {
        setError("No bookmark found for this story.");
        return;
      }
      const startNum = bookmarkChapterNumber + 1;
      if (startNum > totalChapters) {
        setError("Bookmark is at the last chapter, nothing to download.");
        return;
      }
      const countNum = parseInt(count, 10);
      if (isNaN(countNum) || countNum < 1) {
        setError("Please enter a valid number of chapters.");
        return;
      }

      const endNum = Math.min(startNum + countNum - 1, totalChapters);
      setError("");
      onDownload(startNum, endNum);
      onDismiss();
      return;
    }

    const startNum = parseInt(countStart, 10);
    const countNum = parseInt(count, 10);
    if (isNaN(startNum) || isNaN(countNum)) {
      setError("Please enter valid numbers.");
      return;
    }
    if (startNum < 1 || startNum > totalChapters) {
      setError(`Start chapter must be between 1 and ${totalChapters}.`);
      return;
    }
    if (countNum < 1) {
      setError("Chapter count must be at least 1.");
      return;
    }

    const endNum = Math.min(startNum + countNum - 1, totalChapters);
    setError("");
    onDownload(startNum, endNum);
    onDismiss();
  };

  const preview = computePreview(
    mode,
    mode === "count" ? countStart : rangeStart,
    rangeEnd,
    count,
    totalChapters,
    bookmarkChapterNumber,
  );

  return (
    <Portal>
      <Dialog visible onDismiss={onDismiss}>
        <Dialog.Title>Download Range</Dialog.Title>
        <Dialog.Content>
          <Text variant="bodyMedium" style={styles.subtitle}>
            Total Chapters: {totalChapters}
          </Text>
          <SegmentedButtons
            value={mode}
            onValueChange={handleModeChange}
            buttons={[
              { value: "range", label: "Range" },
              {
                value: "bookmark",
                label: "Bookmark",
                disabled: !hasBookmark,
              },
              { value: "count", label: "Count" },
            ]}
            style={styles.segmented}
          />

          {mode === "range" && (
            <View style={styles.rangeRow}>
              <TextInput
                testID="download-range-start-input"
                label="From"
                value={rangeStart}
                onChangeText={setRangeStart}
                keyboardType="number-pad"
                style={[styles.input, styles.rangeInput]}
                mode="outlined"
              />
              <Text style={styles.separator}>-</Text>
              <TextInput
                testID="download-range-end-input"
                label="To"
                value={rangeEnd}
                onChangeText={setRangeEnd}
                keyboardType="number-pad"
                style={[styles.input, styles.rangeInput]}
                mode="outlined"
              />
            </View>
          )}

          {mode === "bookmark" && (
            <View>
              <Text variant="bodyMedium" style={styles.bookmarkInfo}>
                Bookmark at chapter {bookmarkChapterNumber}
              </Text>
              <TextInput
                testID="download-bookmark-count-input"
                label="Chapters to download"
                value={count}
                onChangeText={setCount}
                keyboardType="number-pad"
                mode="outlined"
                style={styles.input}
                right={<TextInput.Affix text="chapters" />}
              />
              <HelperText type="info" visible>
                Downloads from chapter {bookmarkChapterNumber ?? 0 + 1} onward.
              </HelperText>
            </View>
          )}

          {mode === "count" && (
            <View>
              <TextInput
                testID="download-count-start-input"
                label="From chapter"
                value={countStart}
                onChangeText={setCountStart}
                keyboardType="number-pad"
                mode="outlined"
                style={styles.input}
              />
              <TextInput
                testID="download-count-input"
                label="Chapters to download"
                value={count}
                onChangeText={setCount}
                keyboardType="number-pad"
                mode="outlined"
                style={styles.input}
                right={<TextInput.Affix text="chapters" />}
              />
            </View>
          )}

          {preview ? (
            <Text variant="bodyMedium" style={styles.preview}>
              {preview}
            </Text>
          ) : null}

          {error ? (
            <HelperText type="error" visible>
              {error}
            </HelperText>
          ) : null}
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss}>Cancel</Button>
          <Button testID="download-range-download-button" onPress={handleDownload}>
            Download
          </Button>
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
  hasBookmark,
  bookmarkChapterNumber,
}) => {
  if (!visible) return null;
  return (
    <DownloadRangeDialogContent
      key={`download-range-${totalChapters}-${hasBookmark}-${bookmarkChapterNumber ?? 0}`}
      onDismiss={onDismiss}
      onDownload={onDownload}
      totalChapters={totalChapters}
      hasBookmark={hasBookmark}
      bookmarkChapterNumber={bookmarkChapterNumber}
    />
  );
};

const styles = StyleSheet.create({
  subtitle: {
    marginBottom: 12,
  },
  segmented: {
    marginBottom: 16,
  },
  rangeRow: {
    flexDirection: "row",
    alignItems: "center",
  },
  rangeInput: {
    flex: 1,
  },
  input: {
    marginBottom: 12,
  },
  separator: {
    marginHorizontal: 12,
    fontSize: 20,
  },
  bookmarkInfo: {
    marginBottom: 12,
    opacity: 0.8,
  },
  preview: {
    marginTop: 4,
    marginBottom: 8,
    opacity: 0.7,
  },
});
