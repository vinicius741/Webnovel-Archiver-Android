import React, { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { TextInput, Button, Text } from 'react-native-paper';
import { useRouter } from 'expo-router';
import { ScreenContainer } from '../src/components/ScreenContainer';

export default function AddStoryScreen() {
  const router = useRouter();
  const [url, setUrl] = useState('');
  const [loading, setLoading] = useState(false);

  const handleAdd = async () => {
    if (!url) return;
    setLoading(true);
    // TODO: Implement fetching logic
    setTimeout(() => {
      setLoading(false);
      router.back();
    }, 1000);
  };

  return (
    <ScreenContainer>
      <View style={styles.form}>
        <Text variant="titleMedium" style={styles.label}>Webnovel URL</Text>
        <TextInput
          mode="outlined"
          placeholder="https://www.royalroad.com/fiction/..."
          value={url}
          onChangeText={setUrl}
          autoCapitalize="none"
          keyboardType="url"
          style={styles.input}
        />
        <Button 
          mode="contained" 
          onPress={handleAdd} 
          loading={loading}
          disabled={loading || !url}
          style={styles.button}
        >
          Fetch Story
        </Button>
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
  input: {
    marginBottom: 16,
  },
  button: {
    marginTop: 8,
  },
});
