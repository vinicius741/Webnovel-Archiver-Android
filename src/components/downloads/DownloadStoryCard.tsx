import React from "react";
import {
  View,
  StyleSheet,
  TouchableOpacity,
} from "react-native";
import { Text, IconButton, Card, useTheme, Divider } from "react-native-paper";
import { DownloadJob } from "../../services/download/types";
import { DownloadChapterItem } from "./DownloadChapterItem";

export interface StoryGroup {
  storyId: string;
  storyTitle: string;
  jobs: DownloadJob[];
}

interface DownloadStoryCardProps {
  item: StoryGroup;
  isExpanded: boolean;
  onToggleExpanded: (storyId: string) => void;
  onPause: (jobId: string) => void;
  onResume: (jobId: string) => void;
  onCancel: (jobId: string) => void;
  onRetry: (jobId: string) => void;
}

interface StatusSummary {
  downloading: number;
  pending: number;
  paused: number;
  completed: number;
  failed: number;
}

const getStatusSummary = (jobs: DownloadJob[]): StatusSummary => {
  const downloading = jobs.filter((j) => j.status === "downloading").length;
  const pending = jobs.filter((j) => j.status === "pending").length;
  const paused = jobs.filter((j) => j.status === "paused").length;
  const completed = jobs.filter((j) => j.status === "completed").length;
  const failed = jobs.filter((j) => j.status === "failed").length;
  return { downloading, pending, paused, completed, failed };
};

const getSubtitleText = (
  completedCount: number,
  totalCount: number,
  summary: StatusSummary,
): string => {
  const parts = [`${completedCount}/${totalCount} chapters`];
  if (summary.downloading > 0) parts.push(`${summary.downloading} downloading`);
  if (summary.pending > 0) parts.push(`${summary.pending} queued`);
  if (summary.paused > 0) parts.push(`${summary.paused} paused`);
  if (summary.failed > 0) parts.push(`${summary.failed} failed`);
  return parts.join(" • ");
};

export const DownloadStoryCard: React.FC<DownloadStoryCardProps> = ({
  item,
  isExpanded,
  onToggleExpanded,
  onPause,
  onResume,
  onCancel,
  onRetry,
}) => {
  const theme = useTheme();
  const summary = getStatusSummary(item.jobs);
  const completedCount = summary.completed;
  const totalCount = item.jobs.length;
  const hasFailed = summary.failed > 0;
  const isInProgress =
    summary.downloading > 0 || summary.pending > 0 || summary.paused > 0;
  const subtitleText = getSubtitleText(completedCount, totalCount, summary);

  const handlePauseAll = () => {
    item.jobs.forEach((job) => {
      if (job.status === "pending" || job.status === "downloading") {
        onPause(job.id);
      }
    });
  };

  const handleResumeAll = () => {
    item.jobs.forEach((job) => {
      if (job.status === "paused") {
        onResume(job.id);
      }
    });
  };

  const handleCancelAll = () => {
    item.jobs.forEach((job) => {
      if (
        job.status === "pending" ||
        job.status === "paused" ||
        job.status === "downloading"
      ) {
        onCancel(job.id);
      }
    });
  };

  const handleRetryAll = () => {
    item.jobs.forEach((job) => {
      if (job.status === "failed") {
        onRetry(job.id);
      }
    });
  };

  return (
    <Card style={styles.storyCard}>
      <TouchableOpacity onPress={() => onToggleExpanded(item.storyId)}>
        <Card.Content style={styles.storyHeader}>
          <View style={styles.storyTitleRow}>
            <IconButton
              icon={isExpanded ? "chevron-down" : "chevron-right"}
              size={20}
              iconColor={theme.colors.onSurfaceVariant}
              style={styles.expandIcon}
            />
            <View style={styles.storyInfo}>
              <Text
                variant="titleMedium"
                numberOfLines={1}
                style={styles.storyTitle}
              >
                {item.storyTitle}
              </Text>
              <Text variant="bodySmall" style={styles.storySubtitle}>
                {subtitleText}
              </Text>
            </View>
          </View>

          {isInProgress && (
            <View style={styles.storyActions}>
              {(summary.downloading > 0 || summary.pending > 0) && (
                <IconButton
                  icon="pause"
                  size={18}
                  onPress={(e) => {
                    e.stopPropagation();
                    handlePauseAll();
                  }}
                  iconColor={theme.colors.onSurfaceVariant}
                />
              )}
              {summary.paused > 0 && (
                <IconButton
                  icon="play"
                  size={18}
                  onPress={(e) => {
                    e.stopPropagation();
                    handleResumeAll();
                  }}
                  iconColor={theme.colors.primary}
                />
              )}
              <IconButton
                icon="close"
                size={18}
                onPress={(e) => {
                  e.stopPropagation();
                  handleCancelAll();
                }}
                iconColor={theme.colors.error}
              />
            </View>
          )}
          {!isInProgress && hasFailed && (
            <View style={styles.storyActions}>
              <IconButton
                icon="refresh"
                size={18}
                onPress={(e) => {
                  e.stopPropagation();
                  handleRetryAll();
                }}
                iconColor={theme.colors.primary}
              />
            </View>
          )}
        </Card.Content>
      </TouchableOpacity>

      {isExpanded && (
        <View style={styles.chapterList}>
          <Divider />
          {item.jobs.map((job, index) => (
            <View key={job.id}>
              <DownloadChapterItem
                job={job}
                onPause={onPause}
                onResume={onResume}
                onCancel={onCancel}
                onRetry={onRetry}
              />
              {index < item.jobs.length - 1 && (
                <Divider style={styles.chapterDivider} />
              )}
            </View>
          ))}
        </View>
      )}
    </Card>
  );
};

const styles = StyleSheet.create({
  storyCard: {
    elevation: 1,
  },
  storyHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 8,
    paddingHorizontal: 12,
  },
  storyTitleRow: {
    flex: 1,
    flexDirection: "row",
    alignItems: "center",
  },
  expandIcon: {
    margin: 0,
    marginRight: -4,
  },
  storyInfo: {
    flex: 1,
    marginLeft: 4,
  },
  storyTitle: {
    fontWeight: "600",
  },
  storySubtitle: {
    opacity: 0.7,
    marginTop: 2,
  },
  storyActions: {
    flexDirection: "row",
    alignItems: "center",
    marginLeft: 8,
  },
  chapterList: {
    paddingBottom: 8,
  },
  chapterDivider: {
    marginHorizontal: 16,
  },
});
