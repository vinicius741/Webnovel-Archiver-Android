import React from 'react';
import { Text, SegmentedButtons, List, TextInput } from 'react-native-paper';
import { StyleSheet, View } from 'react-native';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { useSettings } from '../src/hooks/useSettings';
import { router } from 'expo-router';

export default function SettingsScreen() {
  const {
      themeMode,
      setThemeMode,
      concurrency,
      delay,
      handleConcurrencyChange,
      handleDelayChange,
      clearData,
  } = useSettings();

  return (
    <ScreenContainer edges={['bottom', 'left', 'right']} style={{ padding: 8 }}>
      <List.Section>
        <List.Subheader>Appearance</List.Subheader>
        <View style={styles.container}>
          <Text variant="bodyMedium" style={styles.label}>Theme</Text>
          <SegmentedButtons
            value={themeMode}
            onValueChange={(value) => setThemeMode(value as any)}
            buttons={[
              {
                value: 'system',
                label: 'System',
                icon: 'theme-light-dark',
              },
              {
                value: 'light',
                label: 'Light',
                icon: 'weather-sunny', 
              },
              {
                value: 'dark',
                label: 'Dark',
                icon: 'weather-night',
              },
            ]}
          />
        </View>
      </List.Section>

      <List.Section>
        <List.Subheader>Downloads</List.Subheader>
        <View style={styles.container}>
          <TextInput
            label="Simultaneous Downloads"
            value={concurrency}
            onChangeText={handleConcurrencyChange}
            keyboardType="number-pad"
            mode="outlined"
            style={styles.input}
            right={<TextInput.Affix text="files" />}
          />
          <TextInput
            label="Delay Between Downloads"
            value={delay}
            onChangeText={handleDelayChange}
            keyboardType="number-pad"
            mode="outlined"
            style={styles.input}
            right={<TextInput.Affix text="ms" />}
          />
        </View>
      </List.Section>
      
      <List.Section>
         <List.Subheader>Data</List.Subheader> 
         <View style={styles.container}>
           <List.Item
              title="Sentence Removal List"
              description="Manage sentences to automatically remove from chapters"
              left={props => <List.Icon {...props} icon="text-box-remove-outline" />}
              onPress={() => router.push('/sentence-removal')}
           />
           <List.Item
              title="Clear Local Storage"
              description="Delete all novels and reset app data"
              left={props => <List.Icon {...props} icon="delete-outline" />}
              onPress={clearData}
           />
         </View>
      </List.Section>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: 16,
    paddingBottom: 8,
  },
  label: {
    marginBottom: 8,
  },
  input: {
    marginBottom: 12,
  },
});