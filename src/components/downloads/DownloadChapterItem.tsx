import React, { useState } from "react";
import { View, StyleSheet } from "react-native";
import { Text, IconButton, useTheme } from "react-native-paper";
import { useScreenLayout } from "../../hooks/common/useScreenLayout";
import { DownloadJob } from "../../services/download/types";
import { getStatusColor, getStatusLabel } from "./downloadStatusUtils";
import { DownloadJobActionHandlers } from "./downloadActionTypes";

interface DownloadChapterItemProps extends DownloadJobActionHandlers {
  job: DownloadJob;
}

type ScreenLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
};

export const DownloadChapterItem: React.FC<DownloadChapterItemProps> = ({
  job,
  onPause,
  onResume,
  onCancel,
  onRemove,
  onRetry,
}) => {
  const theme = useTheme();
  const [expandedError, setExpandedError] = useState(false);
  const layout = useScreenLayout() as ScreenLayout;
  const statusColor = getStatusColor(job.status, theme);
  const isScheduledRetry =
    job.status === "pending" && !!job.nextRetryAt;
  const statusLabel = isScheduledRetry
    ? "Retrying soon"
    : getStatusLabel(job.status);
  const screenWidth = layout.screenWidth || 0;
  const isCompactLayout =
    layout.widthClass === "compact" ||
    (!layout.widthClass && screenWidth > 0 && screenWidth < 420);

  return (
    <View
      style={[
        styles.chapterItem,
        isCompactLayout ? styles.chapterItemCompact : null,
      ]}
    >
      <View style={styles.chapterLeft}>
        <Text
          variant="bodyMedium"
          numberOfLines={isCompactLayout ? 2 : 1}
          style={styles.chapterTitle}
        >
          {job.chapter.title}
        </Text>
        <Text
          variant="labelSmall"
          style={{ color: statusColor }}
        >
          {statusLabel}
          {job.retryCount > 0 ? ` • attempt ${job.retryCount + 1}` : ""}
        </Text>
        {!!job.error && (
          <Text
            variant="bodySmall"
            numberOfLines={expandedError ? undefined : 2}
            onPress={() => setExpandedError((current) => !current)}
            style={[styles.errorText, { color: theme.colors.onSurfaceVariant }]}
          >
            {job.errorCode ? `${job.errorCode}: ` : ""}
            {job.error}
          </Text>
        )}
      </View>
      <View
        style={[
          styles.chapterActions,
          isCompactLayout ? styles.chapterActionsCompact : null,
        ]}
      >
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
              onPress={() => onRemove(job.id)}
              iconColor={theme.colors.onSurfaceVariant}
            />
          </>
        )}
        {job.status === "cancelled" && (
          <IconButton
            icon="close"
            size={16}
            onPress={() => onRemove(job.id)}
            iconColor={theme.colors.onSurfaceVariant}
          />
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
  chapterItemCompact: {
    alignItems: "flex-start",
  },
  chapterLeft: {
    flex: 1,
    marginRight: 8,
  },
  chapterTitle: {
  },
  errorText: {
    marginTop: 2,
  },
  chapterActions: {
    flexDirection: "row",
    alignItems: "center",
  },
  chapterActionsCompact: {
    marginTop: 4,
    marginRight: -8,
  },
});
