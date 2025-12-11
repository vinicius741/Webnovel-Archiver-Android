import React from 'react';
import { StyleSheet, View } from 'react-native';
import { TextInput, Button, Text, IconButton, useTheme } from 'react-native-paper';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { useAddStory } from '../src/hooks/useAddStory';

export default function AddStoryScreen() {
  const {
      url,
      setUrl,
      loading,
      statusMessage,
      handlePaste,
      handleAdd,
  } = useAddStory();

  return (
    <ScreenContainer edges={['bottom', 'left', 'right']} style={{ padding: 8 }}>
      <View style={styles.form}>
        <Text variant="titleMedium" style={styles.label}>Webnovel URL</Text>
        <View style={styles.inputContainer}>
            <TextInput
            mode="outlined"
            placeholder="https://www.royalroad.com/fiction/..."
            value={url}
            onChangeText={setUrl}
            autoCapitalize="none"
            keyboardType="url"
            style={styles.input}
            />
            <IconButton
                icon="content-paste"
                mode="contained-tonal"
                onPress={handlePaste}
                style={styles.pasteButton}
            />
        </View>
        <Button 
          mode="contained" 
          onPress={handleAdd} 
          loading={loading}
          disabled={loading || !url}
          style={styles.button}
        >
          Fetch Story
        </Button>
        {loading && statusMessage ? (
            <Text style={styles.status} variant="bodySmall">{statusMessage}</Text>
        ) : null}
      </View>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  form: {
    paddingTop: 16,
  },
  label: {
    marginBottom: 8,
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
  },
  input: {
    flex: 1,
    marginRight: 8,
  },
  pasteButton: {
    margin: 0,
  },
  button: {
    marginTop: 8,
  },
  status: {
    marginTop: 12,
    textAlign: 'center',
    color: '#666',
  }
});