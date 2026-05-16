import React from "react";
import { View, StyleSheet } from "react-native";
import {
  Text,
  ProgressBar,
  useTheme,
  ActivityIndicator,
} from "react-native-paper";
import { AppButton } from "../theme/AppButton";
import { Story } from "../../types";

interface StoryActionsProps {
  story: Story;
  downloading: boolean;
  syncing: boolean;
  generating: boolean;
  epubProgress: {
    current: number;
    total: number;
    percentage: number;
    stage: string;
    status: string;
  } | null;
  syncStatus?: string;
  downloadProgress: number;
  downloadStatus: string;
  onSync: () => void;
  onGenerate: () => void;
  onRead: () => void;
  onDownloadAll?: () => void;
  onViewDownloads?: () => void;
  stacked?: boolean;
}

export const StoryActions: React.FC<StoryActionsProps> = ({
  story,
  downloading,
  syncing,
  generating,
  epubProgress,
  syncStatus,
  downloadProgress,
  downloadStatus,
  onSync,
  onGenerate,
  onRead,
  onDownloadAll,
  onViewDownloads,
  stacked = false,
}) => {
  const theme = useTheme();
  const hasEpub =
    !!story.epubPath || (story.epubPaths && story.epubPaths.length > 0);
  const showStale = !!story.epubStale && hasEpub;
  const isArchived = !!story.isArchived;

  return (
    <View style={styles.container}>
      <AppButton
        style={styles.actionBtn}
        loading={syncing}
        disabled={syncing || downloading || generating || isArchived}
        onPress={onSync}
        testID="sync-button"
      >
        {syncing ? "Syncing..." : "Sync Chapters"}
      </AppButton>

      {/* Show "Download All" only for stories with zero downloads */}
      {story.downloadedChapters === 0 &&
        story.totalChapters > 0 &&
        !isArchived &&
        onDownloadAll && (
          <AppButton
            icon="download-multiple"
            style={styles.actionBtn}
            disabled={downloading || syncing || generating}
            onPress={onDownloadAll}
            testID="download-all-button"
          >
            Download All
          </AppButton>
        )}

      {downloading && (
        <View style={styles.progressContainer}>
          <ProgressBar
            testID="progress-bar"
            progress={downloadProgress}
            color={theme.colors.primary}
            style={styles.progressBar}
          />
          <Text variant="bodySmall" style={styles.progressText}>
            {downloadStatus}
          </Text>
          {onViewDownloads && (
            <AppButton
              mode="text"
              compact
              onPress={onViewDownloads}
              style={styles.viewDownloadsBtn}
            >
              Go to Downloads Manager
            </AppButton>
          )}
        </View>
      )}

      {syncing && syncStatus && (
        <View style={styles.progressContainer}>
          <ActivityIndicator size="small" style={{ marginBottom: 8 }} />
          <Text variant="bodySmall" style={styles.progressText}>
            {syncStatus}
          </Text>
        </View>
      )}

      <View style={[styles.buttonRow, stacked && styles.buttonRowStacked]} testID="story-actions-row">
        <AppButton
          disabled={story.downloadedChapters === 0 || generating}
          loading={generating}
          onPress={onGenerate}
          style={[styles.actionRowButton, stacked && styles.actionRowButtonStacked]}
          testID="generate-button"
        >
          Generate EPUB
        </AppButton>
        <AppButton
          disabled={!hasEpub || generating}
          onPress={onRead}
          style={[styles.actionRowButton, stacked && styles.actionRowButtonStacked]}
          testID="read-button"
        >
          Read EPUB
        </AppButton>
      </View>

      {showStale ? (
        <Text variant="bodySmall" style={styles.staleText}>
          EPUB out of date
        </Text>
      ) : null}

      {isArchived ? (
        <Text variant="bodySmall" style={styles.archivedText}>
          Archived snapshot: sync and downloads disabled
        </Text>
      ) : null}

      {generating && epubProgress && (
        <View style={styles.progressContainer}>
          <ProgressBar
            testID="progress-bar"
            progress={epubProgress.percentage / 100}
            color={theme.colors.primary}
            style={styles.progressBar}
          />
          <Text variant="bodySmall" style={styles.progressText}>
            {epubProgress.status}
          </Text>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    width: "100%",
  },
  actionBtn: {
    marginBottom: 12,
  },
  buttonRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 12,
    marginBottom: 20,
  },
  buttonRowStacked: {
    flexDirection: "column",
    gap: 12,
  },
  actionRowButton: {
    flex: 1,
    minWidth: 180,
  },
  actionRowButtonStacked: {
    width: "100%",
  },
  progressContainer: {
    marginBottom: 20,
  },
  progressBar: {
    height: 8,
    borderRadius: 4,
  },
  progressText: {
    marginTop: 8,
    textAlign: "center",
  },
  viewDownloadsBtn: {
    marginTop: 4,
  },
  staleText: {
    marginTop: -8,
    marginBottom: 16,
    textAlign: "center",
  },
  archivedText: {
    marginTop: -8,
    marginBottom: 16,
    textAlign: "center",
  },
});
