import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Button, Text, ProgressBar, useTheme, ActivityIndicator } from 'react-native-paper';
import { Story } from '../../types';

interface StoryActionsProps {
    story: Story;
    downloading: boolean;
    checkingUpdates: boolean;
    updateStatus?: string;
    downloadProgress: number;
    downloadStatus: string;
    onDownloadOrUpdate: () => void;
    onGenerateOrRead: () => void;
}

export const StoryActions: React.FC<StoryActionsProps> = ({
    story,
    downloading,
    checkingUpdates,
    updateStatus,
    downloadProgress,
    downloadStatus,
    onDownloadOrUpdate,
    onGenerateOrRead
}) => {
    const theme = useTheme();

    return (
        <View style={styles.container}>
            <View style={styles.buttonRow}>
                <Button 
                    mode="contained" 
                    icon={downloading ? undefined : (story.downloadedChapters === story.totalChapters ? 'refresh' : 'download')}
                    style={[styles.actionBtn, { flex: 1, borderRadius: 12 }]} 
                    loading={downloading || checkingUpdates}
                    disabled={downloading || checkingUpdates}
                    onPress={onDownloadOrUpdate}
                >
                    {downloading ? 'Downloading' : (story.downloadedChapters === story.totalChapters ? (checkingUpdates ? 'Checking' : 'Update') : 'Download All')}
                </Button>

                <Button
                    mode="outlined"
                    icon={story.epubPath ? 'book-open-page-variant' : 'file-export'}
                    style={[styles.actionBtn, { flex: 1, borderRadius: 12 }]}
                    disabled={story.downloadedChapters === 0 && !story.epubPath}
                    onPress={onGenerateOrRead}
                >
                    {story.epubPath ? 'Read EPUB' : 'Generate'}
                </Button>
            </View>

            {downloading && (
                <View style={styles.progressContainer}>
                    <ProgressBar progress={downloadProgress} color={theme.colors.primary} style={styles.progressBar} />
                    <View style={styles.progressLabelRow}>
                        <Text variant="labelSmall" style={[styles.progressText, { color: theme.colors.onSurfaceVariant }]}>
                            {downloadStatus}
                        </Text>
                        <Text variant="labelSmall" style={[styles.progressText, { color: theme.colors.primary, fontWeight: 'bold' }]}>
                            {Math.round(downloadProgress * 100)}%
                        </Text>
                    </View>
                </View>
            )}

            {checkingUpdates && updateStatus && !downloading && (
                <View style={styles.progressContainer}>
                     <View style={styles.checkingRow}>
                        <ActivityIndicator size={12} color={theme.colors.primary} />
                        <Text variant="labelSmall" style={[styles.progressText, { color: theme.colors.onSurfaceVariant, marginLeft: 8 }]}>
                            {updateStatus}
                        </Text>
                     </View>
                </View>
            )}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        width: '100%',
        paddingHorizontal: 16,
        paddingBottom: 8,
    },
    buttonRow: {
        flexDirection: 'row',
        gap: 12,
        marginBottom: 16,
    },
    actionBtn: {
        height: 48,
        justifyContent: 'center',
    },
    progressContainer: {
        marginBottom: 16,
        backgroundColor: 'rgba(79, 70, 229, 0.05)',
        padding: 12,
        borderRadius: 12,
    },
    progressBar: {
        height: 6,
        borderRadius: 3,
    },
    progressLabelRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        marginTop: 8,
    },
    progressText: {
        textAlign: 'center',
    },
    checkingRow: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
    }
});
