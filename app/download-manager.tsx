import React, { useState, useCallback } from 'react';
import { StyleSheet, View } from 'react-native';
import { Text, useTheme, Card, IconButton } from 'react-native-paper';
import { Stack } from 'expo-router';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { useDownloadQueue } from '../src/hooks/useDownloadQueue';
import { DownloadQueueList } from '../src/components/downloads/DownloadQueueList';
import { useAppAlert } from '../src/context/AlertContext';

interface StatItemProps {
    icon: string;
    value: number;
    label: string;
    color?: string;
    theme: any;
}

const StatItem = ({ icon, value, label, color, theme }: StatItemProps) => (
    <View style={styles.statItem}>
        <IconButton
            icon={icon}
            size={20}
            iconColor={color || theme.colors.onSurfaceVariant}
            style={styles.statIcon}
        />
        <Text variant="titleLarge" style={[styles.statValue, { color: color || theme.colors.onSurface }]}>
            {value}
        </Text>
        <Text variant="bodySmall" style={[styles.statLabel, { color: theme.colors.onSurfaceVariant }]}>
            {label}
        </Text>
    </View>
);

export default function DownloadManagerScreen() {
    const theme = useTheme();
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

    const [refreshing, setRefreshing] = useState(false);

    const onRefresh = useCallback(() => {
        setRefreshing(true);
        refreshState();
        setTimeout(() => setRefreshing(false), 300);
    }, [refreshState]);

    const hasActiveJobs = stats.pending > 0 || stats.active > 0;
    const hasPausedJobs = stats.paused > 0;
    const hasCompletedJobs = stats.completed > 0;
    const hasFailedJobs = stats.failed > 0;

    const handleCancelAll = () => {
        showAlert(
            'Cancel All Downloads',
            'Are you sure you want to cancel all active and pending downloads? This action cannot be undone.',
            [
                { text: 'Cancel', style: 'cancel' },
                { text: 'Confirm', style: 'destructive', onPress: cancelAll }
            ]
        );
    };

    const handleClearCompleted = () => {
        showAlert(
            'Clear Completed Downloads',
            'Remove all completed downloads from the queue?',
            [
                { text: 'Cancel', style: 'cancel' },
                { text: 'Clear', style: 'default', onPress: clearCompleted }
            ]
        );
    };

    return (
        <ScreenContainer edges={['bottom', 'left', 'right']}>
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
                            {hasCompletedJobs && (
                                <IconButton
                                    icon="delete-sweep"
                                    size={24}
                                    onPress={handleClearCompleted}
                                    iconColor={theme.colors.onSurfaceVariant}
                                />
                            )}
                        </View>
                    ),
                }} 
            />

            <Card style={styles.statsCard}>
                <Card.Content style={styles.statsContent}>
                    <StatItem 
                        icon="download" 
                        value={stats.active} 
                        label="Active" 
                        color={theme.colors.primary}
                        theme={theme}
                    />
                    <View style={[styles.statDivider, { backgroundColor: theme.colors.outlineVariant }]} />
                    <StatItem 
                        icon="clock-outline" 
                        value={stats.pending} 
                        label="Queued"
                        theme={theme}
                    />
                    <View style={[styles.statDivider, { backgroundColor: theme.colors.outlineVariant }]} />
                    <StatItem 
                        icon="pause" 
                        value={stats.paused} 
                        label="Paused"
                        theme={theme}
                    />
                    <View style={[styles.statDivider, { backgroundColor: theme.colors.outlineVariant }]} />
                    <StatItem 
                        icon="check-circle" 
                        value={stats.completed} 
                        label="Done" 
                        color={theme.colors.secondary}
                        theme={theme}
                    />
                    <View style={[styles.statDivider, { backgroundColor: theme.colors.outlineVariant }]} />
                    <StatItem 
                        icon="alert-circle" 
                        value={stats.failed} 
                        label="Failed" 
                        color={hasFailedJobs ? theme.colors.error : undefined}
                        theme={theme}
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
            />
        </ScreenContainer>
    );
}

const styles = StyleSheet.create({
    headerActions: {
        flexDirection: 'row',
        alignItems: 'center',
        marginRight: -8,
    },
    statsCard: {
        margin: 16,
        marginBottom: 8,
    },
    statsContent: {
        flexDirection: 'row',
        justifyContent: 'space-around',
        alignItems: 'center',
        paddingVertical: 8,
    },
    statItem: {
        alignItems: 'center',
        flex: 1,
    },
    statIcon: {
        margin: 0,
        marginBottom: -4,
    },
    statValue: {
        fontWeight: 'bold',
    },
    statLabel: {
        marginTop: -2,
    },
    statDivider: {
        width: 1,
        height: 40,
        opacity: 0.5,
    },
});
