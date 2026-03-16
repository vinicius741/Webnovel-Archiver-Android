import React, { useMemo, useState } from "react";
import {
  View,
  FlatList,
  StyleSheet,
  RefreshControl,
  TouchableOpacity,
} from "react-native";
import { Text, useTheme, Divider, IconButton, Card } from "react-native-paper";
import { DownloadJob } from "../../services/download/types";

interface StoryGroup {
  storyId: string;
  storyTitle: string;
  jobs: DownloadJob[];
}

interface DownloadQueueListProps {
  jobsByStory: StoryGroup[];
  onPause: (jobId: string) => void;
  onResume: (jobId: string) => void;
  onCancel: (jobId: string) => void;
  onRetry: (jobId: string) => void;
  refreshing: boolean;
  onRefresh: () => void;
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

export const DownloadQueueList: React.FC<DownloadQueueListProps> = ({
  jobsByStory,
  onPause,
  onResume,
  onCancel,
  onRetry,
  refreshing,
  onRefresh,
}) => {
  const theme = useTheme();
  const [expandedStories, setExpandedStories] = useState<Set<string>>(
    new Set(),
  );

  const validStoryIds = useMemo(
    () => new Set(jobsByStory.map((group) => group.storyId)),
    [jobsByStory],
  );

  const toggleStoryExpanded = (storyId: string) => {
    setExpandedStories((previous) => {
      const next = new Set(
        Array.from(previous).filter((id) => validStoryIds.has(id)),
      );
      if (next.has(storyId)) {
        next.delete(storyId);
      } else {
        next.add(storyId);
      }
      return next;
    });
  };

  if (jobsByStory.length === 0) {
    return (
      <View style={styles.emptyContainer}>
        <IconButton
          icon="download-box-outline"
          size={64}
          iconColor={theme.colors.surfaceVariant}
          style={styles.emptyIcon}
        />
        <Text variant="titleLarge" style={styles.emptyTitle}>
          No Active Downloads
        </Text>
        <Text variant="bodyMedium" style={styles.emptyText}>
          Downloaded chapters will appear here
        </Text>
      </View>
    );
  }

  const handleStoryPauseAll = (jobs: DownloadJob[]) => {
    jobs.forEach((job) => {
      if (job.status === "pending" || job.status === "downloading") {
        onPause(job.id);
      }
    });
  };

  const handleStoryResumeAll = (jobs: DownloadJob[]) => {
    jobs.forEach((job) => {
      if (job.status === "paused") {
        onResume(job.id);
      }
    });
  };

  const handleStoryCancelAll = (jobs: DownloadJob[]) => {
    jobs.forEach((job) => {
      if (
        job.status === "pending" ||
        job.status === "paused" ||
        job.status === "downloading"
      ) {
        onCancel(job.id);
      }
    });
  };

  const handleStoryRetryAll = (jobs: DownloadJob[]) => {
    jobs.forEach((job) => {
      if (job.status === "failed") {
        onRetry(job.id);
      }
    });
  };

  const renderChapterItem = (job: DownloadJob) => {
    const statusColors = {
      pending: theme.colors.secondary,
      downloading: theme.colors.primary,
      paused: theme.colors.tertiary,
      completed: theme.colors.secondary,
      failed: theme.colors.error,
    };

    const statusLabels = {
      pending: "Queued",
      downloading: "Downloading",
      paused: "Paused",
      completed: "Done",
      failed: "Failed",
    };

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
            style={{ color: statusColors[job.status] }}
          >
            {statusLabels[job.status]}
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

  const renderStoryCard = ({ item }: { item: StoryGroup }) => {
    const isExpanded =
      validStoryIds.has(item.storyId) && expandedStories.has(item.storyId);
    const summary = getStatusSummary(item.jobs);
    const completedCount = item.jobs.filter(
      (j) => j.status === "completed",
    ).length;
    const totalCount = item.jobs.length;
    const hasFailed = summary.failed > 0;
    const isInProgress =
      summary.downloading > 0 || summary.pending > 0 || summary.paused > 0;
    const subtitleText = getSubtitleText(completedCount, totalCount, summary);

    return (
      <Card style={styles.storyCard}>
        <TouchableOpacity onPress={() => toggleStoryExpanded(item.storyId)}>
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
                      handleStoryPauseAll(item.jobs);
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
                      handleStoryResumeAll(item.jobs);
                    }}
                    iconColor={theme.colors.primary}
                  />
                )}
                <IconButton
                  icon="close"
                  size={18}
                  onPress={(e) => {
                    e.stopPropagation();
                    handleStoryCancelAll(item.jobs);
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
                    handleStoryRetryAll(item.jobs);
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
                {renderChapterItem(job)}
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

  return (
    <FlatList
      data={jobsByStory}
      keyExtractor={(item) => item.storyId}
      renderItem={renderStoryCard}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={onRefresh}
          colors={[theme.colors.primary]}
        />
      }
      contentContainerStyle={styles.listContent}
      ItemSeparatorComponent={() => <View style={styles.cardSpacing} />}
    />
  );
};

const styles = StyleSheet.create({
  listContent: {
    flexGrow: 1,
    paddingHorizontal: 16,
    paddingTop: 8,
    paddingBottom: 16,
  },
  cardSpacing: {
    height: 8,
  },
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
  chapterDivider: {
    marginHorizontal: 16,
  },
  emptyContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
    padding: 32,
  },
  emptyIcon: {
    marginBottom: 16,
  },
  emptyTitle: {
    marginBottom: 8,
    opacity: 0.7,
  },
  emptyText: {
    textAlign: "center",
    opacity: 0.5,
  },
});
