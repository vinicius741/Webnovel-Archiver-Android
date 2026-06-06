import React, { useState, useCallback } from "react";
import { Portal } from "react-native-paper";
import { Stack } from "expo-router";
import { ScreenContainer } from "../src/components/common/ScreenContainer";
import { useDownloadQueue } from "../src/hooks/downloads/useDownloadQueue";
import { useDownloadManagerLayout } from "../src/hooks/downloads/useDownloadManagerLayout";
import { DownloadQueueList } from "../src/components/downloads/DownloadQueueList";
import { DownloadStatsBar } from "../src/components/downloads/DownloadStatsBar";
import { DownloadManagerHeaderActions } from "../src/components/downloads/DownloadManagerHeaderActions";
import { DownloadManagerSettingsModal } from "../src/components/downloads/DownloadManagerSettingsModal";
import { useAppAlert } from "../src/context/AlertContext";
import { useSettings } from "../src/hooks/common/useSettings";
import { ErrorBoundary } from "../src/components/common/ErrorBoundary";

export default function DownloadManagerScreen() {
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
    clearFinished,
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
    saveSettings,
    selectedSource,
    setSelectedSource,
    availableProviders,
    handleResetSource,
  } = useSettings();

  const [refreshing, setRefreshing] = useState(false);
  const [settingsModalVisible, setSettingsModalVisible] = useState(false);

  const { shellPadding, queueMaxWidth, statItemStyle } =
    useDownloadManagerLayout();

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    refreshState();
    setTimeout(() => setRefreshing(false), 300);
  }, [refreshState]);

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

  const handleClearFinishedPress = () => {
    showAlert(
      "Clear Finished Downloads",
      "Remove completed and failed downloads from the queue?",
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Clear",
          style: "default",
          onPress: () => {
            clearFinished();
            void saveSettings().finally(() => {
              setSettingsModalVisible(false);
            });
          },
        },
      ],
    );
  };

  const handleSettingsDismiss = useCallback(() => {
    void saveSettings().finally(() => {
      setSettingsModalVisible(false);
    });
  }, [saveSettings]);

  return (
    <ErrorBoundary contextLabel="Download Manager">
      <ScreenContainer edges={["bottom", "left", "right"]}>
        <Stack.Screen
          options={{
            headerRight: () => (
              <DownloadManagerHeaderActions
                stats={stats}
                onPauseAll={pauseAll}
                onResumeAll={resumeAll}
                onCancelAll={handleCancelAll}
                onOpenSettings={() => setSettingsModalVisible(true)}
              />
            ),
          }}
        />

        <DownloadStatsBar
          stats={stats}
          statItemStyle={statItemStyle}
          shellPadding={shellPadding}
          queueMaxWidth={queueMaxWidth}
        />

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
            onDismiss={handleSettingsDismiss}
            concurrency={concurrency}
            concurrencyError={concurrencyError}
            delay={delay}
            delayError={delayError}
            onConcurrencyChange={handleConcurrencyChange}
            onDelayChange={handleDelayChange}
            onConcurrencyBlur={handleConcurrencyBlur}
            onDelayBlur={handleDelayBlur}
            onClearFinishedPress={handleClearFinishedPress}
            selectedSource={selectedSource}
            onSourceSelect={setSelectedSource}
            availableProviders={availableProviders}
            onResetSource={handleResetSource}
          />
        </Portal>
      </ScreenContainer>
    </ErrorBoundary>
  );
}
