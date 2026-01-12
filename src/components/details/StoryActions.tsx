import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Button, Text, ProgressBar, useTheme, ActivityIndicator } from 'react-native-paper';
import { Story } from '../../types';

interface StoryActionsProps {
    story: Story;
    downloading: boolean;
    checkingUpdates: boolean;
    generating: boolean;
    epubProgress: { current: number; total: number; percentage: number; stage: string; status: string } | null;
    updateStatus?: string;
    downloadProgress: number;
    downloadStatus: string;
    onDownloadAll: () => void;
    onUpdate: () => void;
    onGenerateOrRead: () => void;
    onPartialDownload: () => void;
}

export const StoryActions: React.FC<StoryActionsProps> = ({
    story,
    downloading,
    checkingUpdates,
    generating,
    epubProgress,
    updateStatus,
    downloadProgress,
    downloadStatus,
    onDownloadAll,
    onUpdate,
    onGenerateOrRead,
    onPartialDownload
}) => {
    const theme = useTheme();

    return (
        <View style={styles.container}>
            <Button
                mode="contained"
                style={styles.actionBtn}
                loading={downloading}
                disabled={downloading || checkingUpdates || story.downloadedChapters === story.totalChapters}
                onPress={onDownloadAll}
                testID="download-button"
            >
                {downloading ? 'Downloading...' : 'Download All'}
            </Button>

            {downloading && (
                <View style={styles.progressContainer}>
                    <ProgressBar progress={downloadProgress} color={theme.colors.primary} style={styles.progressBar} />
                    <Text variant="bodySmall" style={styles.progressText}>
                        {downloadStatus}
                    </Text>
                </View>
            )}

            <Button
                mode="outlined"
                style={styles.actionBtn}
                loading={checkingUpdates}
                disabled={downloading || checkingUpdates || generating}
                onPress={onUpdate}
                testID="update-button"
            >
                {checkingUpdates ? 'Checking...' : 'Update'}
            </Button>

            {checkingUpdates && updateStatus && (
                <View style={styles.progressContainer}>
                     <ActivityIndicator size="small" style={{ marginBottom: 8 }} />
                    <Text variant="bodySmall" style={styles.progressText}>
                        {updateStatus}
                    </Text>
                </View>
            )}

            <Button
                mode="outlined"
                style={styles.actionBtn}
                disabled={(story.downloadedChapters === 0 && !story.epubPath) || generating}
                loading={generating}
                onPress={onGenerateOrRead}
                testID="generate-button"
            >
                {story.epubPath ? 'Read EPUB' : 'Generate EPUB'}
            </Button>

            {generating && epubProgress && (
                <View style={styles.progressContainer}>
                    <ProgressBar progress={epubProgress.percentage / 100} color={theme.colors.primary} style={styles.progressBar} />
                    <Text variant="bodySmall" style={styles.progressText}>
                        {epubProgress.status}
                    </Text>
                </View>
            )}

            <Button
                mode="outlined"
                onPress={onPartialDownload}
                disabled={downloading || checkingUpdates || generating}
                style={styles.actionBtn}
                testID="partial-download-button"
            >
                Partial Download
            </Button>
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
    }
});
