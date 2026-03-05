import React from 'react';
import { View, SectionList, StyleSheet, RefreshControl } from 'react-native';
import { Text, useTheme, Divider } from 'react-native-paper';
import { DownloadQueueItem } from './DownloadQueueItem';
import { DownloadJob } from '../../services/download/types';

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
    refreshing: boolean;
    onRefresh: () => void;
}

export const DownloadQueueList: React.FC<DownloadQueueListProps> = ({
    jobsByStory,
    onPause,
    onResume,
    onCancel,
    refreshing,
    onRefresh,
}) => {
    const theme = useTheme();

    if (jobsByStory.length === 0) {
        return (
            <View style={styles.emptyContainer}>
                <Text variant="titleMedium" style={styles.emptyTitle}>
                    No Active Downloads
                </Text>
                <Text variant="bodyMedium" style={styles.emptyText}>
                    Downloaded chapters will appear here
                </Text>
            </View>
        );
    }

    const sections = jobsByStory.map(group => ({
        title: group.storyTitle,
        data: group.jobs,
    }));

    const renderSectionHeader = ({ section }: { section: { title: string; data: DownloadJob[] } }) => {
        const completedCount = section.data.filter(j => j.status === 'completed').length;
        const totalCount = section.data.length;
        
        return (
            <View style={[styles.sectionHeader, { backgroundColor: theme.colors.surfaceVariant }]}>
                <Text variant="titleSmall" numberOfLines={1} style={styles.sectionTitle}>
                    {section.title}
                </Text>
                <Text variant="bodySmall" style={styles.sectionCount}>
                    {completedCount}/{totalCount} done
                </Text>
            </View>
        );
    };

    const renderItem = ({ item }: { item: DownloadJob }) => (
        <DownloadQueueItem
            job={item}
            onPause={() => onPause(item.id)}
            onResume={() => onResume(item.id)}
            onCancel={() => onCancel(item.id)}
        />
    );

    const renderSectionFooter = () => <Divider />;

    return (
        <SectionList
            sections={sections}
            keyExtractor={(item) => item.id}
            renderItem={renderItem}
            renderSectionHeader={renderSectionHeader}
            renderSectionFooter={renderSectionFooter}
            stickySectionHeadersEnabled
            refreshControl={
                <RefreshControl
                    refreshing={refreshing}
                    onRefresh={onRefresh}
                />
            }
            contentContainerStyle={styles.listContent}
        />
    );
};

const styles = StyleSheet.create({
    listContent: {
        flexGrow: 1,
    },
    sectionHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingHorizontal: 16,
        paddingVertical: 8,
    },
    sectionTitle: {
        flex: 1,
        fontWeight: '600',
    },
    sectionCount: {
        opacity: 0.7,
    },
    emptyContainer: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        padding: 32,
    },
    emptyTitle: {
        marginBottom: 8,
        opacity: 0.7,
    },
    emptyText: {
        textAlign: 'center',
        opacity: 0.5,
    },
});
