import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Text, ProgressBar, IconButton, Chip, useTheme } from 'react-native-paper';
import { DownloadJob, JobStatus } from '../../services/download/types';

interface DownloadQueueItemProps {
    job: DownloadJob;
    onPause: () => void;
    onResume: () => void;
    onCancel: () => void;
}

const getStatusColor = (status: JobStatus, theme: any): string => {
    switch (status) {
        case 'pending':
            return theme.colors.secondary;
        case 'downloading':
            return theme.colors.primary;
        case 'paused':
            return theme.dark ? '#FFB74D' : '#FFA000';
        case 'completed':
            return theme.dark ? '#81C784' : '#4CAF50';
        case 'failed':
            return theme.colors.error;
        default:
            return theme.colors.secondary;
    }
};

const getStatusLabel = (status: JobStatus): string => {
    switch (status) {
        case 'pending': return 'Queued';
        case 'downloading': return 'Downloading';
        case 'paused': return 'Paused';
        case 'completed': return 'Done';
        case 'failed': return 'Failed';
        default: return status;
    }
};

export const DownloadQueueItem: React.FC<DownloadQueueItemProps> = ({
    job,
    onPause,
    onResume,
    onCancel,
}) => {
    const theme = useTheme();
    const statusColor = getStatusColor(job.status, theme);

    const renderActions = () => {
        switch (job.status) {
            case 'pending':
            case 'downloading':
                return (
                    <IconButton
                        icon="pause"
                        size={20}
                        onPress={onPause}
                    />
                );
            case 'paused':
                return (
                    <IconButton
                        icon="play"
                        size={20}
                        onPress={onResume}
                    />
                );
            case 'failed':
                return (
                    <IconButton
                        icon="close"
                        size={20}
                        onPress={onCancel}
                    />
                );
            default:
                return null;
        }
    };

    const canCancel = job.status === 'pending' || job.status === 'paused' || job.status === 'downloading';

    return (
        <View style={styles.container}>
            <View style={styles.content}>
                <View style={styles.header}>
                    <Text variant="titleSmall" numberOfLines={1} style={styles.chapterTitle}>
                        {job.chapter.title}
                    </Text>
                    <Chip
                        mode="flat"
                        textStyle={{ fontSize: 10, color: 'white' }}
                        style={[styles.statusChip, { backgroundColor: statusColor }]}
                    >
                        {getStatusLabel(job.status)}
                    </Chip>
                </View>
                
                <View style={styles.progressContainer}>
                    <ProgressBar
                        progress={job.status === 'completed' ? 1 : 0}
                        color={job.status === 'failed' ? theme.colors.error : theme.colors.primary}
                        style={styles.progressBar}
                    />
                </View>

                {job.status === 'failed' && job.error && (
                    <Text variant="bodySmall" style={[styles.errorText, { color: theme.colors.error }]}>
                        {job.error}
                    </Text>
                )}
            </View>

            <View style={styles.actions}>
                {renderActions()}
                {canCancel && (
                    <IconButton
                        icon="close"
                        size={20}
                        onPress={onCancel}
                    />
                )}
            </View>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 8,
        paddingHorizontal: 12,
        borderBottomWidth: StyleSheet.hairlineWidth,
        borderBottomColor: '#e0e0e0',
    },
    content: {
        flex: 1,
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 4,
    },
    chapterTitle: {
        flex: 1,
        marginRight: 8,
    },
    statusChip: {
        height: 20,
    },
    progressContainer: {
        marginTop: 2,
    },
    progressBar: {
        height: 4,
        borderRadius: 2,
    },
    errorText: {
        marginTop: 4,
    },
    actions: {
        flexDirection: 'row',
        alignItems: 'center',
    },
});
