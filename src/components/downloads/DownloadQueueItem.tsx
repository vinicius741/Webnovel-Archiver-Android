import React, { useState } from "react";
import { View, StyleSheet, TouchableOpacity } from "react-native";
import {
  Text,
  ProgressBar,
  IconButton,
  Chip,
  useTheme,
} from "react-native-paper";
import { DownloadJob, JobStatus } from "../../services/download/types";

interface DownloadQueueItemProps {
  job: DownloadJob;
  onPause: () => void;
  onResume: () => void;
  onCancel: () => void;
  onRetry: () => void;
}

const getStatusColor = (status: JobStatus, theme: any): string => {
  switch (status) {
    case "pending":
      return theme.colors.secondary;
    case "downloading":
      return theme.colors.primary;
    case "paused":
      return theme.colors.tertiary;
    case "completed":
      return theme.colors.secondary;
    case "failed":
      return theme.colors.error;
    default:
      return theme.colors.secondary;
  }
};

const getStatusLabel = (status: JobStatus): string => {
  switch (status) {
    case "pending":
      return "Queued";
    case "downloading":
      return "Downloading";
    case "paused":
      return "Paused";
    case "completed":
      return "Done";
    case "failed":
      return "Failed";
    default:
      return status;
  }
};

export const DownloadQueueItem: React.FC<DownloadQueueItemProps> = ({
  job,
  onPause,
  onResume,
  onCancel,
  onRetry,
}) => {
  const theme = useTheme();
  const [showError, setShowError] = useState(false);
  const statusColor = getStatusColor(job.status, theme);

  const renderActions = () => {
    switch (job.status) {
      case "pending":
      case "downloading":
        return (
          <IconButton
            icon="pause"
            size={20}
            onPress={onPause}
            iconColor={theme.colors.onSurfaceVariant}
          />
        );
      case "paused":
        return (
          <IconButton
            icon="play"
            size={20}
            onPress={onResume}
            iconColor={theme.colors.primary}
          />
        );
      case "failed":
        return (
          <>
            <IconButton
              icon="refresh"
              size={20}
              onPress={onRetry}
              iconColor={theme.colors.primary}
            />
            <IconButton
              icon="close"
              size={20}
              onPress={onCancel}
              iconColor={theme.colors.onSurfaceVariant}
            />
          </>
        );
      default:
        return null;
    }
  };

  const canCancel =
    job.status === "pending" ||
    job.status === "paused" ||
    job.status === "downloading";

  return (
    <View style={styles.container}>
      <View style={styles.content}>
        <View style={styles.header}>
          <Text
            variant="bodyMedium"
            numberOfLines={1}
            style={styles.chapterTitle}
          >
            {job.chapter.title}
          </Text>
          <Chip
            mode="flat"
            textStyle={{ fontSize: 10, color: "white", fontWeight: "500" }}
            style={[styles.statusChip, { backgroundColor: statusColor }]}
          >
            {getStatusLabel(job.status)}
          </Chip>
        </View>

        <View style={styles.progressContainer}>
          <ProgressBar
            progress={
              job.status === "completed"
                ? 1
                : job.status === "downloading"
                  ? 0.5
                  : 0
            }
            color={
              job.status === "failed"
                ? theme.colors.error
                : theme.colors.primary
            }
            style={styles.progressBar}
          />
        </View>

        {job.status === "failed" && job.error && (
          <TouchableOpacity onPress={() => setShowError(!showError)}>
            <Text
              variant="bodySmall"
              style={[styles.errorText, { color: theme.colors.error }]}
              numberOfLines={showError ? undefined : 1}
            >
              {job.error}
            </Text>
            <Text
              variant="labelSmall"
              style={[styles.tapHint, { color: theme.colors.onSurfaceVariant }]}
            >
              {showError ? "Tap to collapse" : "Tap to expand"}
            </Text>
          </TouchableOpacity>
        )}
      </View>

      <View style={styles.actions}>
        {renderActions()}
        {canCancel && (
          <IconButton
            icon="close"
            size={20}
            onPress={onCancel}
            iconColor={theme.colors.onSurfaceVariant}
          />
        )}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    alignItems: "flex-start",
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
  content: {
    flex: 1,
    marginRight: 8,
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: 6,
  },
  chapterTitle: {
    flex: 1,
    marginRight: 8,
    fontWeight: "500",
  },
  statusChip: {
    height: 22,
    borderRadius: 4,
  },
  progressContainer: {
    marginTop: 2,
  },
  progressBar: {
    height: 4,
    borderRadius: 2,
  },
  errorText: {
    marginTop: 6,
  },
  tapHint: {
    marginTop: 2,
    opacity: 0.6,
  },
  actions: {
    flexDirection: "row",
    alignItems: "center",
    marginTop: -4,
  },
});
