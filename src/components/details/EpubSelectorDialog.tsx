import React from 'react';
import { StyleSheet, View, FlatList } from 'react-native';
import { Button, Dialog, Portal, Text, List } from 'react-native-paper';

interface EpubSelectorDialogProps {
    visible: boolean;
    onDismiss: () => void;
    onSelect: (path: string) => void;
    epubs: string[];
}

export const EpubSelectorDialog: React.FC<EpubSelectorDialogProps> = ({
    visible,
    onDismiss,
    onSelect,
    epubs,
}) => {
    // Helper to extract filename from path
    const getFilename = (path: string) => {
        try {
            // First decode URL-encoded parts (like %2F and %3A)
            const decodedPath = decodeURIComponent(path);

            // Split by both '/' and ':' to handle typical paths and Android SAF URIs
            const parts = decodedPath.split(/[\/:]/);
            const filename = parts.pop() || 'Unknown File';

            // Remove common epub extensions and replace underscores with spaces for readability
            return filename.replace(/\.epub$/i, '').replace(/_/g, ' ');
        } catch (e) {
            return path.split('/').pop() || 'Unknown File';
        }
    };

    return (
        <Portal>
            <Dialog visible={visible} onDismiss={onDismiss}>
                <Dialog.Title>Select EPUB to Read</Dialog.Title>
                <Dialog.Content>
                    <Text variant="bodyMedium" style={styles.subtitle}>
                        This story has multiple EPUB files. Please select one to read:
                    </Text>
                    <View style={styles.listContainer}>
                        {epubs.length === 0 ? (
                            <Text variant="bodyMedium" style={styles.emptyText}>
                                No EPUB files found.
                            </Text>
                        ) : (
                            <FlatList
                                data={epubs}
                                keyExtractor={(item) => item}
                                nestedScrollEnabled={true}
                                renderItem={({ item }) => (
                                    <List.Item
                                        title={getFilename(item)}
                                        titleNumberOfLines={2}
                                        onPress={() => {
                                            onDismiss();
                                            onSelect(item);
                                        }}
                                        accessibilityLabel={`Select ${getFilename(item)}`}
                                        left={props => <List.Icon {...props} icon="book-open-page-variant" />}
                                        style={styles.listItem}
                                    />
                                )}
                            />
                        )}
                    </View>
                </Dialog.Content>
                <Dialog.Actions>
                    <Button onPress={onDismiss}>Cancel</Button>
                </Dialog.Actions>
            </Dialog>
        </Portal>
    );
};

const styles = StyleSheet.create({
    subtitle: {
        marginBottom: 16,
    },
    listContainer: {
        maxHeight: 300,
    },
    listItem: {
        paddingHorizontal: 0,
    },
    emptyText: {
        textAlign: 'center',
        opacity: 0.7,
        marginVertical: 20,
    }
});
