import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Button, Text, ProgressBar, useTheme } from 'react-native-paper';
import { Story } from '../../types';

interface StoryActionsProps {
    story: Story;
    downloading: boolean;
    checkingUpdates: boolean;
    downloadProgress: number;
    downloadStatus: string;
    onDownloadOrUpdate: () => void;
    onGenerateOrRead: () => void;
}

export const StoryActions: React.FC<StoryActionsProps> = ({
    story,
    downloading,
    checkingUpdates,
    downloadProgress,
    downloadStatus,
    onDownloadOrUpdate,
    onGenerateOrRead
}) => {
    const theme = useTheme();

    return (
        <View style={styles.container}>
            <Button 
                mode="contained" 
                style={styles.actionBtn} 
                loading={downloading}
                disabled={downloading}
                onPress={onDownloadOrUpdate}
            >
                {downloading ? 'Downloading...' : (story.downloadedChapters === story.totalChapters ? (checkingUpdates ? 'Checking...' : 'Update') : 'Download All')}
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
                disabled={story.downloadedChapters === 0 && !story.epubPath}
                onPress={onGenerateOrRead}
            >
                {story.epubPath ? 'Read EPUB' : 'Generate EPUB'}
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
