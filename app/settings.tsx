import React from 'react';
import { Text, SegmentedButtons, List } from 'react-native-paper';
import { StyleSheet, View, Alert } from 'react-native';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { storageService } from '../src/services/StorageService';
import { useTheme } from '../src/theme/ThemeContext';

export default function SettingsScreen() {
  const { themeMode, setThemeMode } = useTheme();

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
  }
});
