import React from 'react';
import { Text, SegmentedButtons, List } from 'react-native-paper';
import { StyleSheet, View } from 'react-native';
import { ScreenContainer } from '../src/components/ScreenContainer';
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
           <Text variant="bodyMedium">Backup & Config coming soon.</Text>
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
