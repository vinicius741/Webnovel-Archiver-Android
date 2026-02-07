import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Button, Text, ProgressBar, useTheme, ActivityIndicator } from 'react-native-paper';
import { Story } from '../../types';

interface StoryActionsProps {
    story: Story;
    downloading: boolean;
    syncing: boolean;
    generating: boolean;
    epubProgress: { current: number; total: number; percentage: number; stage: string; status: string } | null;
    syncStatus?: string;
    downloadProgress: number;
    downloadStatus: string;
    onSync: () => void;
    onGenerateOrRead: () => void;
    onDownloadAll?: () => void;
}

export const StoryActions: React.FC<StoryActionsProps> = ({
    story,
    downloading,
    syncing,
    generating,
    epubProgress,
    syncStatus,
    downloadProgress,
    downloadStatus,
    onSync,
    onGenerateOrRead,
    onDownloadAll,
}) => {
    const theme = useTheme();
    const hasEpub = !!story.epubPath || (story.epubPaths && story.epubPaths.length > 0);
    const showStale = !!story.epubStale && hasEpub;

    return (
        <View style={styles.container}>
            <Button
                mode="contained"
                style={styles.actionBtn}
                loading={syncing}
                disabled={syncing || downloading || generating}
                onPress={onSync}
                testID="sync-button"
            >
                {syncing ? 'Syncing...' : 'Sync Chapters'}
            </Button>

            {/* Show "Download All" only for stories with zero downloads */}
            {story.downloadedChapters === 0 && story.totalChapters > 0 && onDownloadAll && (
                <Button
                    mode="contained-tonal"
                    icon="download-multiple"
                    style={styles.actionBtn}
                    disabled={downloading || syncing || generating}
                    onPress={onDownloadAll}
                    testID="download-all-button"
                >
                    Download All
                </Button>
            )}

            {downloading && (
                <View style={styles.progressContainer}>
                    <ProgressBar testID="progress-bar" progress={downloadProgress} color={theme.colors.primary} style={styles.progressBar} />
                    <Text variant="bodySmall" style={styles.progressText}>
                        {downloadStatus}
                    </Text>
                </View>
            )}

            {syncing && syncStatus && (
                <View style={styles.progressContainer}>
                    <ActivityIndicator size="small" style={{ marginBottom: 8 }} />
                    <Text variant="bodySmall" style={styles.progressText}>
                        {syncStatus}
                    </Text>
                </View>
            )}

            <Button
                mode="outlined"
                style={styles.actionBtn}
                disabled={(story.downloadedChapters === 0 && !hasEpub) || generating}
                loading={generating}
                onPress={onGenerateOrRead}
                testID="generate-button"
            >
                Read EPUB
            </Button>

            {showStale ? (
                <Text variant="bodySmall" style={styles.staleText}>
                    EPUB out of date
                </Text>
            ) : null}

            {generating && epubProgress && (
                <View style={styles.progressContainer}>
                    <ProgressBar testID="progress-bar" progress={epubProgress.percentage / 100} color={theme.colors.primary} style={styles.progressBar} />
                    <Text variant="bodySmall" style={styles.progressText}>
                        {epubProgress.status}
                    </Text>
                </View>
            )}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        width: '100%',
    },
    actionBtn: {
        marginBottom: 20,
    },
    progressContainer: {
        marginBottom: 20,
    },
    progressBar: {
        height: 8,
        borderRadius: 4,
    },
    progressText: {
        marginTop: 8, 
        textAlign: 'center',
    },
    staleText: {
        marginTop: -8,
        marginBottom: 16,
        textAlign: 'center',
    }
});
