import React from 'react';
import { Text, SegmentedButtons, List, TextInput } from 'react-native-paper';
import { StyleSheet, View, ScrollView } from 'react-native';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { useSettings } from '../src/hooks/useSettings';
import { router } from 'expo-router';

export default function SettingsScreen() {
  const {
    themeMode,
    setThemeMode,
    concurrency,
    delay,
    concurrencyError,
    delayError,
    handleConcurrencyChange,
    handleDelayChange,
    handleConcurrencyBlur,
    handleDelayBlur,
    clearData,
    handleExportBackup,
    handleImportBackup,
  } = useSettings();

  return (
    <ScreenContainer edges={['bottom', 'left', 'right']} style={{ padding: 8 }}>
      <ScrollView contentContainerStyle={{ paddingBottom: 80 }}>
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
              onEndEditing={handleConcurrencyBlur}
              keyboardType="number-pad"
              mode="outlined"
              style={styles.input}
              error={!!concurrencyError}
              right={<TextInput.Affix text="files" />}
            />
            {concurrencyError ? <Text variant="bodySmall" style={styles.error}>{concurrencyError}</Text> : null}
            <TextInput
              label="Delay Between Downloads"
              value={delay}
              onChangeText={handleDelayChange}
              onEndEditing={handleDelayBlur}
              keyboardType="number-pad"
              mode="outlined"
              style={styles.input}
              error={!!delayError}
              right={<TextInput.Affix text="ms" />}
            />
            {delayError ? <Text variant="bodySmall" style={styles.error}>{delayError}</Text> : null}
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

        <List.Section>
          <List.Subheader>Backup</List.Subheader>
          <View style={styles.container}>
            <List.Item
              title="Export Backup"
              description="Export your library to a JSON file"
              left={props => <List.Icon {...props} icon="export-variant" />}
              onPress={handleExportBackup}
            />
            <List.Item
              title="Import Backup"
              description="Merge library from a backup file"
              left={props => <List.Icon {...props} icon="import" />}
              onPress={handleImportBackup}
            />
          </View>
        </List.Section>
      </ScrollView>
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
  error: {
    marginTop: -8,
    marginBottom: 12,
    color: 'red',
  },
});
