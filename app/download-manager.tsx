import React, { useState, useCallback } from 'react';
import { StyleSheet, View } from 'react-native';
import { Appbar, Text, useTheme, Chip } from 'react-native-paper';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { useDownloadQueue } from '../src/hooks/useDownloadQueue';
import { DownloadQueueList } from '../src/components/downloads/DownloadQueueList';

export default function DownloadManagerScreen() {
    const theme = useTheme();
    const {
        jobsByStory,
        stats,
        pauseJob,
        resumeJob,
        cancelJob,
        pauseAll,
        resumeAll,
        cancelAll,
        clearCompleted,
        refreshState,
    } = useDownloadQueue();

    const [refreshing, setRefreshing] = useState(false);

    const onRefresh = useCallback(() => {
        setRefreshing(true);
        // State updates synchronously, but we show the spinner briefly for UX feedback
        refreshState();
        setTimeout(() => setRefreshing(false), 300);
    }, [refreshState]);

    const hasActiveJobs = stats.pending > 0 || stats.active > 0;
    const hasPausedJobs = stats.paused > 0;
    const hasCompletedJobs = stats.completed > 0;

    return (
        <ScreenContainer edges={['bottom', 'left', 'right']}>
            <View style={styles.statsContainer}>
                <View style={styles.statsRow}>
                    <Chip icon="download" style={styles.statChip}>
                        {stats.active} Active
                    </Chip>
                    <Chip icon="clock-outline" style={styles.statChip}>
                        {stats.pending} Queued
                    </Chip>
                    <Chip icon="pause" style={styles.statChip}>
                        {stats.paused} Paused
                    </Chip>
                </View>
                <View style={styles.statsRow}>
                    <Chip icon="check" style={[styles.statChip, { backgroundColor: theme.dark ? '#81C784' : '#4CAF50' }]} textStyle={{ color: 'white' }}>
                        {stats.completed} Done
                    </Chip>
                    <Chip icon="alert-circle" style={[styles.statChip, { backgroundColor: theme.colors.error }]} textStyle={{ color: 'white' }}>
                        {stats.failed} Failed
                    </Chip>
                </View>
            </View>

            <View style={styles.actionsContainer}>
                {hasActiveJobs && (
                    <Appbar.Action
                        icon="pause-circle"
                        onPress={pauseAll}
                        iconColor={theme.colors.primary}
                    />
                )}
                {hasPausedJobs && (
                    <Appbar.Action
                        icon="play-circle"
                        onPress={resumeAll}
                        iconColor={theme.colors.primary}
                    />
                )}
                <Appbar.Action
                    icon="stop-circle"
                    onPress={cancelAll}
                    iconColor={theme.colors.error}
                />
                {hasCompletedJobs && (
                    <Appbar.Action
                        icon="delete-sweep"
                        onPress={clearCompleted}
                        iconColor={theme.colors.secondary}
                    />
                )}
            </View>

            <DownloadQueueList
                jobsByStory={jobsByStory}
                onPause={pauseJob}
                onResume={resumeJob}
                onCancel={cancelJob}
                refreshing={refreshing}
                onRefresh={onRefresh}
            />
        </ScreenContainer>
    );
}

const styles = StyleSheet.create({
    statsContainer: {
        padding: 12,
        borderBottomWidth: StyleSheet.hairlineWidth,
        borderBottomColor: '#e0e0e0',
    },
    statsRow: {
        flexDirection: 'row',
        marginBottom: 8,
    },
    statChip: {
        marginRight: 8,
    },
    actionsContainer: {
        flexDirection: 'row',
        justifyContent: 'flex-end',
        paddingHorizontal: 8,
        paddingVertical: 4,
    },
});
