import { useEffect } from 'react';
import { Stack } from 'expo-router';
import { PaperProvider } from 'react-native-paper';
import { useColorScheme } from 'react-native';
import { LightTheme } from '../src/theme/light';
import { DarkTheme } from '../src/theme/dark';
import { registerBackgroundFetchAsync } from '../src/services/BackgroundTaskService';

export default function RootLayout() {
  const colorScheme = useColorScheme();
  const theme = colorScheme === 'dark' ? DarkTheme : LightTheme;

  useEffect(() => {
    registerBackgroundFetchAsync().catch(err => 
      console.error("Failed to register background fetch", err)
    );
  }, []);

  return (
    <PaperProvider theme={theme}>
      <Stack
        screenOptions={{
          headerStyle: {
            backgroundColor: theme.colors.elevation.level2,
          },
          headerTintColor: theme.colors.onSurface,
          headerTitleStyle: {
            fontWeight: 'bold',
          },
        }}
      >
        <Stack.Screen name="index" options={{ title: 'Library' }} />
        <Stack.Screen name="add" options={{ title: 'Add Story', presentation: 'modal' }} />
        <Stack.Screen name="settings" options={{ title: 'Settings' }} />
      </Stack>
    </PaperProvider>
  );
}
