import React from 'react';
import { Text, SegmentedButtons, List, TextInput } from 'react-native-paper';
import { StyleSheet, View, Alert } from 'react-native';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { storageService } from '../src/services/StorageService';
import { useTheme } from '../src/theme/ThemeContext';

export default function SettingsScreen() {
  const { themeMode, setThemeMode } = useTheme();
  const [concurrency, setConcurrency] = React.useState('1');
  const [delay, setDelay] = React.useState('500');

  React.useEffect(() => {
    loadSettings();
  }, []);

  const loadSettings = async () => {
    const settings = await storageService.getSettings();
    setConcurrency(settings.downloadConcurrency.toString());
    setDelay(settings.downloadDelay.toString());
  };

  const saveSettings = async (newConcurrency: string, newDelay: string) => {
    const downloadConcurrency = parseInt(newConcurrency) || 1;
    const downloadDelay = parseInt(newDelay) || 0;
    
    // Validate limits if needed
    const finalConcurrency = Math.max(1, Math.min(10, downloadConcurrency));
    const finalDelay = Math.max(0, downloadDelay);

    await storageService.saveSettings({
        downloadConcurrency: finalConcurrency,
        downloadDelay: finalDelay
    });
  };

  const handleConcurrencyChange = (text: string) => {
      setConcurrency(text);
      saveSettings(text, delay);
  };

  const handleDelayChange = (text: string) => {
      setDelay(text);
      saveSettings(concurrency, text);
  };

  return (
    <ScreenContainer>
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
              title="Clear Local Storage"
              description="Delete all novels and reset app data"
              left={props => <List.Icon {...props} icon="delete-outline" />}
              onPress={() => {
                Alert.alert(
                  'Clear Data',
                  'Are you sure you want to delete all novels and settings? This action cannot be undone.',
                  [
                    { text: 'Cancel', style: 'cancel' },
                    { 
                      text: 'Delete', 
                      style: 'destructive',
                      onPress: async () => {
                        await storageService.clearAll();
                      }
                    }
                  ]
                );
              }}
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
