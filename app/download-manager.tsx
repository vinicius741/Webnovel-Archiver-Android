import React, { useState, useCallback, useMemo } from "react";
import { StyleSheet, View } from "react-native";
import { useTheme, Card, IconButton, Portal } from "react-native-paper";
import { Stack } from "expo-router";
import { ScreenContainer } from "../src/components/ScreenContainer";
import { useDownloadQueue } from "../src/hooks/useDownloadQueue";
import { DownloadQueueList } from "../src/components/downloads/DownloadQueueList";
import { StatItem } from "../src/components/downloads/StatItem";
import { DownloadManagerSettingsModal } from "../src/components/downloads/DownloadManagerSettingsModal";
import { useAppAlert } from "../src/context/AlertContext";
import { useScreenLayout } from "../src/hooks/useScreenLayout";
import { useSettings } from "../src/hooks/useSettings";

type ScreenLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
};

export default function DownloadManagerScreen() {
  const theme = useTheme();
  const layout = useScreenLayout() as ScreenLayout;
  const { showAlert } = useAppAlert();
  const {
    jobsByStory,
    stats,
    pauseJob,
    resumeJob,
    cancelJob,
    retryJob,
    pauseAll,
    resumeAll,
    cancelAll,
    clearCompleted,
    refreshState,
  } = useDownloadQueue();

  const {
    concurrency,
    delay,
    concurrencyError,
    delayError,
    handleConcurrencyChange,
    handleDelayChange,
    handleConcurrencyBlur,
    handleDelayBlur,
  } = useSettings();

  const [refreshing, setRefreshing] = useState(false);
  const [settingsModalVisible, setSettingsModalVisible] = useState(false);
  const screenWidth = layout.screenWidth || 0;
  const widthClass =
    layout.widthClass ||
    (screenWidth >= 960
      ? "expanded"
      : screenWidth >= 600
        ? "medium"
        : "compact");

  const shellPadding = useMemo(() => {
    if (widthClass === "expanded") {
      return 32;
    }
    if (widthClass === "medium") {
      return 24;
    }
    return 16;
  }, [widthClass]);

  const queueMaxWidth = widthClass === "expanded" ? 1080 : 920;
  const MIN_STAT_WIDTH = 88;
  const GAP = 8;

  const cardWidth =
    screenWidth > 0
      ? Math.min(queueMaxWidth, Math.max(screenWidth - shellPadding * 2, 0))
      : queueMaxWidth;
  const contentWidth = Math.max(0, cardWidth - 32);

  const maxCols = Math.floor(
    (contentWidth + GAP) / (MIN_STAT_WIDTH + GAP),
  );
  const statsColumns = maxCols >= 5 ? 5 : maxCols >= 3 ? 3 : 1;

  const statItemWidth = Math.max(
    MIN_STAT_WIDTH,
    Math.floor(
      (contentWidth - (statsColumns - 1) * GAP) / statsColumns,
    ),
  );
  const statItemStyle = useMemo(
    () => ({
      width: statItemWidth,
    }),
    [statItemWidth],
  );

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    refreshState();
    setTimeout(() => setRefreshing(false), 300);
  }, [refreshState]);

  const hasActiveJobs = stats.pending > 0 || stats.active > 0;
  const hasPausedJobs = stats.paused > 0;
  const hasFailedJobs = stats.failed > 0;

  const handleCancelAll = () => {
    showAlert(
      "Cancel All Downloads",
      "Are you sure you want to cancel all active and pending downloads? This action cannot be undone.",
      [
        { text: "Cancel", style: "cancel" },
        { text: "Confirm", style: "destructive", onPress: cancelAll },
      ],
    );
  };

  const handleClearCompletedPress = () => {
    showAlert(
      "Clear Completed Downloads",
      "Remove all completed downloads from the queue?",
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Clear",
          style: "default",
          onPress: () => {
            clearCompleted();
            setSettingsModalVisible(false);
          },
        },
      ],
    );
  };

  return (
    <ScreenContainer edges={["bottom", "left", "right"]}>
      <Stack.Screen
        options={{
          headerRight: () => (
            <View style={styles.headerActions}>
              {hasActiveJobs && (
                <IconButton
                  icon="pause-circle"
                  size={24}
                  onPress={pauseAll}
                  iconColor={theme.colors.onSurface}
                />
              )}
              {hasPausedJobs && (
                <IconButton
                  icon="play-circle"
                  size={24}
                  onPress={resumeAll}
                  iconColor={theme.colors.onSurface}
                />
              )}
              {(hasActiveJobs || hasPausedJobs) && (
                <IconButton
                  icon="stop-circle"
                  size={24}
                  onPress={handleCancelAll}
                  iconColor={theme.colors.error}
                />
              )}
              <IconButton
                icon="tune"
                size={24}
                onPress={() => setSettingsModalVisible(true)}
                iconColor={theme.colors.onSurfaceVariant}
              />
            </View>
          ),
        }}
      />

      <Card
        style={[
          styles.statsCard,
          {
            marginHorizontal: shellPadding,
            maxWidth: queueMaxWidth,
            alignSelf: "center",
          },
        ]}
      >
        <Card.Content style={styles.statsContent}>
          <StatItem
            icon="download"
            value={stats.active}
            label="Active"
            color={theme.colors.primary}
            theme={theme}
            style={statItemStyle}
          />
          <StatItem
            icon="clock-outline"
            value={stats.pending}
            label="Queued"
            theme={theme}
            style={statItemStyle}
          />
          <StatItem
            icon="pause"
            value={stats.paused}
            label="Paused"
            theme={theme}
            style={statItemStyle}
          />
          <StatItem
            icon="check-circle"
            value={stats.completed}
            label="Done"
            color={theme.colors.secondary}
            theme={theme}
            style={statItemStyle}
          />
          <StatItem
            icon="alert-circle"
            value={stats.failed}
            label="Failed"
            color={hasFailedJobs ? theme.colors.error : undefined}
            theme={theme}
            style={statItemStyle}
          />
        </Card.Content>
      </Card>

      <DownloadQueueList
        jobsByStory={jobsByStory}
        onPause={pauseJob}
        onResume={resumeJob}
        onCancel={cancelJob}
        onRetry={retryJob}
        refreshing={refreshing}
        onRefresh={onRefresh}
        contentMaxWidth={queueMaxWidth}
        horizontalPadding={shellPadding}
      />

      <Portal>
        <DownloadManagerSettingsModal
          visible={settingsModalVisible}
          onDismiss={() => setSettingsModalVisible(false)}
          concurrency={concurrency}
          concurrencyError={concurrencyError}
          delay={delay}
          delayError={delayError}
          onConcurrencyChange={handleConcurrencyChange}
          onDelayChange={handleDelayChange}
          onConcurrencyBlur={handleConcurrencyBlur}
          onDelayBlur={handleDelayBlur}
          onClearCompletedPress={handleClearCompletedPress}
        />
      </Portal>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  headerActions: {
    flexDirection: "row",
    alignItems: "center",
    marginRight: -8,
  },
  statsCard: {
    marginTop: 8,
    marginBottom: 8,
    width: "100%",
  },
  statsContent: {
    flexDirection: "row",
    flexWrap: "wrap",
    justifyContent: "center",
    gap: 8,
    paddingVertical: 12,
  },
});
