import React, { useState, useEffect } from 'react';
import { View, StyleSheet } from 'react-native';
import { Button, Dialog, Portal, TextInput, Text, HelperText } from 'react-native-paper';

interface DownloadRangeDialogProps {
    visible: boolean;
    onDismiss: () => void;
    onDownload: (start: number, end: number) => void;
    totalChapters: number;
}

export const DownloadRangeDialog: React.FC<DownloadRangeDialogProps> = ({
    visible,
    onDismiss,
    onDownload,
    totalChapters
}) => {
    const [start, setStart] = useState('1');
    const [end, setEnd] = useState('');
    const [error, setError] = useState('');

    useEffect(() => {
        if (visible) {
            setStart('1');
            setEnd(totalChapters.toString());
            setError('');
        }
    }, [visible, totalChapters]);

    const handleDownload = () => {
        const startNum = parseInt(start, 10);
        const endNum = parseInt(end, 10);

        if (isNaN(startNum) || isNaN(endNum)) {
            setError('Please enter valid numbers');
            return;
        }

        if (startNum < 1 || endNum > totalChapters) {
            setError(`Range must be between 1 and ${totalChapters}`);
            return;
        }

        if (startNum > endNum) {
            setError('Start chapter cannot be greater than end chapter');
            return;
        }

        setError('');
        onDownload(startNum, endNum);
        onDismiss();
    };

    return (
        <Portal>
            <Dialog visible={visible} onDismiss={onDismiss}>
                <Dialog.Title>Download Range</Dialog.Title>
                <Dialog.Content>
                    <Text variant="bodyMedium" style={{ marginBottom: 16 }}>
                        Total Chapters: {totalChapters}
                    </Text>
                    <View style={styles.inputContainer}>
                        <TextInput
                            label="From"
                            value={start}
                            onChangeText={setStart}
                            keyboardType="number-pad"
                            style={styles.input}
                            mode="outlined"
                        />
                        <Text style={styles.separator}>-</Text>
                        <TextInput
                            label="To"
                            value={end}
                            onChangeText={setEnd}
                            keyboardType="number-pad"
                            style={styles.input}
                            mode="outlined"
                        />
                    </View>
                    {error ? (
                        <HelperText type="error" visible={!!error}>
                            {error}
                        </HelperText>
                    ) : null}
                </Dialog.Content>
                <Dialog.Actions>
                    <Button onPress={onDismiss}>Cancel</Button>
                    <Button onPress={handleDownload}>Download</Button>
                </Dialog.Actions>
            </Dialog>
        </Portal>
    );
};

const styles = StyleSheet.create({
    inputContainer: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    input: {
        flex: 1,
    },
    separator: {
        marginHorizontal: 16,
        fontSize: 20,
    },
});
