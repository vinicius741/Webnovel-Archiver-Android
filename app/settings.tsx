import React from 'react';
import { Text } from 'react-native-paper';
import { ScreenContainer } from '../src/components/ScreenContainer';

export default function SettingsScreen() {
  return (
    <ScreenContainer>
      <Text variant="headlineSmall">Settings</Text>
      <Text variant="bodyMedium">Backup & Config coming soon.</Text>
    </ScreenContainer>
  );
}
