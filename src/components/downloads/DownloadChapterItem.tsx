import React from "react";
import { View, StyleSheet } from "react-native";
import { Text, IconButton, useTheme } from "react-native-paper";
import { DownloadJob } from "../../services/download/types";
import { getStatusColor, getStatusLabel } from "./downloadStatusUtils";

interface DownloadChapterItemProps {
  job: DownloadJob;
  onPause: (jobId: string) => void;
  onResume: (jobId: string) => void;
  onCancel: (jobId: string) => void;
  onRetry: (jobId: string) => void;
}

export const DownloadChapterItem: React.FC<DownloadChapterItemProps> = ({
  job,
  onPause,
  onResume,
  onCancel,
  onRetry,
}) => {
  const theme = useTheme();
  const statusColor = getStatusColor(job.status, theme);
  const statusLabel = getStatusLabel(job.status);

  return (
    <View style={styles.chapterItem}>
      <View style={styles.chapterLeft}>
        <Text
          variant="bodyMedium"
          numberOfLines={1}
          style={styles.chapterTitle}
        >
          {job.chapter.title}
        </Text>
        <Text
          variant="labelSmall"
          style={{ color: statusColor }}
        >
          {statusLabel}
        </Text>
      </View>
      <View style={styles.chapterActions}>
        {(job.status === "pending" || job.status === "downloading") && (
          <IconButton
            icon="pause"
            size={16}
            onPress={() => onPause(job.id)}
            iconColor={theme.colors.onSurfaceVariant}
          />
        )}
        {job.status === "paused" && (
          <IconButton
            icon="play"
            size={16}
            onPress={() => onResume(job.id)}
            iconColor={theme.colors.primary}
          />
        )}
        {job.status === "failed" && (
          <>
            <IconButton
              icon="refresh"
              size={16}
              onPress={() => onRetry(job.id)}
              iconColor={theme.colors.primary}
            />
            <IconButton
              icon="close"
              size={16}
              onPress={() => onCancel(job.id)}
              iconColor={theme.colors.onSurfaceVariant}
            />
          </>
        )}
        {(job.status === "pending" || job.status === "paused") && (
          <IconButton
            icon="close"
            size={16}
            onPress={() => onCancel(job.id)}
            iconColor={theme.colors.onSurfaceVariant}
          />
        )}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  chapterItem: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  chapterLeft: {
    flex: 1,
    marginRight: 8,
  },
  chapterTitle: {
    fontWeight: "500",
  },
  chapterActions: {
    flexDirection: "row",
    alignItems: "center",
  },
});
