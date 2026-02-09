import React, { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Button, Dialog, HelperText, Portal, Switch, Text, TextInput } from 'react-native-paper';

import { EPUB_MAX_CHAPTERS_MIN, EPUB_MAX_CHAPTERS_MAX } from '../../constants/epub';
import { EpubConfig } from '../../types';

interface EpubConfigDialogProps {
    visible: boolean;
    onDismiss: () => void;
    onSave: (config: EpubConfig) => Promise<void> | void;
    initialConfig: EpubConfig;
    totalChapters: number;
    downloadedChapterCount: number;
    hasBookmark: boolean;
}

export const EpubConfigDialog: React.FC<EpubConfigDialogProps> = ({
    visible,
    onDismiss,
    onSave,
    initialConfig,
    totalChapters,
    downloadedChapterCount,
    hasBookmark,
}) => {
    const [maxChapters, setMaxChapters] = useState('');
    const [rangeStart, setRangeStart] = useState('');
    const [rangeEnd, setRangeEnd] = useState('');
    const [startAfterBookmark, setStartAfterBookmark] = useState(false);
    const [error, setError] = useState('');

    useEffect(() => {
        if (!visible) return;

        setMaxChapters(String(initialConfig.maxChaptersPerEpub));
        setRangeStart(String(initialConfig.rangeStart));
        setRangeEnd(String(initialConfig.rangeEnd));
        setStartAfterBookmark(hasBookmark ? initialConfig.startAfterBookmark : false);
        setError('');
    }, [visible, initialConfig, hasBookmark]);

    const handleSave = async () => {
        if (totalChapters <= 0) {
            setError('No chapters available yet.');
            return;
        }

        const parsedMax = parseInt(maxChapters, 10);
        const parsedStart = parseInt(rangeStart, 10);
        const parsedEnd = parseInt(rangeEnd, 10);

        if (Number.isNaN(parsedMax) || Number.isNaN(parsedStart) || Number.isNaN(parsedEnd)) {
            setError('Please enter valid numbers.');
            return;
        }

        if (parsedMax < EPUB_MAX_CHAPTERS_MIN || parsedMax > EPUB_MAX_CHAPTERS_MAX) {
            setError(`Max chapters must be between ${EPUB_MAX_CHAPTERS_MIN} and ${EPUB_MAX_CHAPTERS_MAX}.`);
            return;
        }

        if (parsedStart < 1 || parsedEnd > totalChapters) {
            setError(`Range must be between 1 and ${totalChapters}.`);
            return;
        }

        if (parsedStart > parsedEnd) {
            setError('Range start cannot be greater than range end.');
            return;
        }

        setError('');
        await onSave({
            maxChaptersPerEpub: parsedMax,
            rangeStart: parsedStart,
            rangeEnd: parsedEnd,
            startAfterBookmark: hasBookmark ? startAfterBookmark : false,
        });
        onDismiss();
    };

    return (
        <Portal>
            <Dialog visible={visible} onDismiss={onDismiss}>
                <Dialog.Title>EPUB Settings</Dialog.Title>
                <Dialog.Content>
                    <Text variant="bodyMedium" style={styles.subtitle}>
                        Configure how this story is split into EPUB files.
                    </Text>
                    <TextInput
                        testID="epub-max-input"
                        label="Max Chapters Per EPUB"
                        value={maxChapters}
                        onChangeText={setMaxChapters}
                        keyboardType="number-pad"
                        mode="outlined"
                        style={styles.input}
                        right={<TextInput.Affix text="chapters" />}
                    />
                    <View style={styles.rangeRow}>
                        <TextInput
                            testID="epub-range-start-input"
                            label="From"
                            value={rangeStart}
                            onChangeText={setRangeStart}
                            keyboardType="number-pad"
                            mode="outlined"
                            style={[styles.input, styles.rangeInput]}
                        />
                        <Text style={styles.separator}>-</Text>
                        <TextInput
                            testID="epub-range-end-input"
                            label="To"
                            value={rangeEnd}
                            onChangeText={setRangeEnd}
                            keyboardType="number-pad"
                            mode="outlined"
                            style={[styles.input, styles.rangeInput]}
                        />
                    </View>
                    <View style={styles.switchRow}>
                        <Text variant="bodyMedium">Start after bookmark</Text>
                        <Switch
                            testID="epub-bookmark-switch"
                            value={startAfterBookmark}
                            onValueChange={setStartAfterBookmark}
                            disabled={!hasBookmark}
                        />
                    </View>
                    {!hasBookmark ? (
                        <HelperText type="info" visible>
                            No bookmark found for this story.
                        </HelperText>
                    ) : null}
                    <Text variant="bodySmall" style={styles.hint}>
                        Available chapters: 1-{Math.max(totalChapters, 0)}
                    </Text>
                    <Text variant="bodySmall" style={styles.hint}>
                        Downloaded chapters: {downloadedChapterCount}. EPUB generation only includes downloaded chapters.
                    </Text>
                    {error ? (
                        <HelperText type="error" visible>
                            {error}
                        </HelperText>
                    ) : null}
                </Dialog.Content>
                <Dialog.Actions>
                    <Button onPress={onDismiss}>Cancel</Button>
                    <Button testID="epub-save-button" onPress={handleSave}>Save</Button>
                </Dialog.Actions>
            </Dialog>
        </Portal>
    );
};

const styles = StyleSheet.create({
    subtitle: {
        marginBottom: 16,
    },
    input: {
        marginBottom: 12,
    },
    rangeRow: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    rangeInput: {
        flex: 1,
    },
    separator: {
        marginHorizontal: 12,
        fontSize: 20,
    },
    switchRow: {
        marginTop: 4,
        marginBottom: 8,
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
    },
    hint: {
        opacity: 0.7,
    },
});
