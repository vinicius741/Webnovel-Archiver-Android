import React, { useState, useEffect } from 'react';
import { StyleSheet, View, FlatList, Alert } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  Appbar,
  List,
  IconButton,
  FAB,
  Portal,
  Dialog,
  TextInput,
  Button,
  Text,
  ActivityIndicator,
  Divider
} from 'react-native-paper';
import { router, Stack } from 'expo-router';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { storageService } from '../src/services/StorageService';
import { useTheme } from '../src/theme/ThemeContext';
import * as Clipboard from 'expo-clipboard';
import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';

export default function SentenceRemovalScreen() {
  const insets = useSafeAreaInsets();
  const [sentences, setSentences] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [visible, setVisible] = useState(false);
  const [newSentence, setNewSentence] = useState('');
  const [editingIndex, setEditingIndex] = useState<number | null>(null);

  useEffect(() => {
    loadSentences();
  }, []);

  const loadSentences = async () => {
    setLoading(true);
    const list = await storageService.getSentenceRemovalList();
    setSentences(list);
    setLoading(false);
  };

  const saveSentences = async (list: string[]) => {
    setSentences(list);
    await storageService.saveSentenceRemovalList(list);
  };

  const handleExport = async () => {
    try {
      const json = JSON.stringify(sentences, null, 4);
      const file = new File(Paths.cache, 'sentence_removal_list.json');

      if (!file.exists) {
        file.create();
      }
      
      file.write(json);

      if (await Sharing.isAvailableAsync()) {
        await Sharing.shareAsync(file.uri, {
            mimeType: 'application/json',
            dialogTitle: 'Export Sentence Removal List',
            UTI: 'public.json'
        });
      } else {
        Alert.alert('Error', 'Sharing is not available on this device');
      }
    } catch (error) {
      console.error(error);
      Alert.alert('Export Failed', 'Failed to export the file.');
    }
  };

  const handleAdd = () => {
    const trimmed = newSentence.trim();
    if (!trimmed) return;

    const existingIndex = sentences.indexOf(trimmed);
    if (existingIndex !== -1) {
      if (editingIndex === null || existingIndex !== editingIndex) {
        Alert.alert('Duplicate', 'This sentence is already in the removal list.');
        return;
      }
    }

    const list = [...sentences];
    if (editingIndex !== null) {
      list[editingIndex] = trimmed;
    } else {
      list.unshift(trimmed);
    }

    saveSentences(list);
    setVisible(false);
    setNewSentence('');
    setEditingIndex(null);
  };

  const confirmDelete = (index: number) => {
    Alert.alert(
      'Remove Sentence',
      'Are you sure you want to remove this sentence from the blocklist?',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            const list = sentences.filter((_, i) => i !== index);
            saveSentences(list);
          }
        }
      ]
    );
  };

  const openDialog = (sentence: string = '', index: number | null = null) => {
    setNewSentence(sentence);
    setEditingIndex(index);
    setVisible(true);
  };

  const renderItem = ({ item, index }: { item: string, index: number }) => (
    <List.Item
      title={item}
      titleNumberOfLines={3}
      right={props => (
        <View style={{ flexDirection: 'row' }}>
          <IconButton
            icon="pencil"
            onPress={() => openDialog(item, index)}
          />
          <IconButton
            icon="delete"
            iconColor="red"
            onPress={() => confirmDelete(index)}
          />
        </View>
      )}
      style={styles.listItem}
    />
  );

  return (
    <ScreenContainer edges={['bottom', 'left', 'right']}>
      <Stack.Screen options={{ headerShown: false }} />
      <Appbar.Header>
        <Appbar.BackAction onPress={() => router.back()} />
        <Appbar.Content title="Sentence Removal" />
        <Appbar.Action icon="export-variant" onPress={handleExport} accessibilityLabel="Export JSON" />
      </Appbar.Header>

      {loading ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
        </View>
      ) : (
        <FlatList
          data={sentences}
          keyExtractor={(item, index) => index.toString()}
          renderItem={renderItem}
          ItemSeparatorComponent={() => <Divider />}
          contentContainerStyle={styles.listContent}
          ListEmptyComponent={
            <View style={styles.emptyContainer}>
              <Text variant="bodyLarge">No sentences in the list.</Text>
            </View>
          }
        />
      )}

      <FAB
        icon="plus"
        style={[styles.fab, { bottom: insets.bottom + 16 }]}
        onPress={() => openDialog()}
        label="Add Sentence"
      />

      <Portal>
        <Dialog visible={visible} onDismiss={() => setVisible(false)}>
          <Dialog.Title>{editingIndex !== null ? 'Edit Sentence' : 'Add Sentence'}</Dialog.Title>
          <Dialog.Content>
            <TextInput
              label="Sentence to remove"
              value={newSentence}
              onChangeText={setNewSentence}
              mode="outlined"
              multiline
              numberOfLines={3}
              autoFocus
            />
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setVisible(false)}>Cancel</Button>
            <Button onPress={handleAdd}>Save</Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  listContent: {
    paddingBottom: 120,
  },
  listItem: {
    paddingVertical: 8,
  },
  fab: {
    position: 'absolute',
    margin: 16,
    right: 0,
    bottom: 0,
  },
  emptyContainer: {
    padding: 32,
    alignItems: 'center',
  }
});
