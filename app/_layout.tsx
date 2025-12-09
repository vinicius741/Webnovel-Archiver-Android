import { useEffect } from 'react';
import { Stack } from 'expo-router';
import { useTheme as usePaperTheme } from 'react-native-paper';
import { registerBackgroundFetchAsync } from '../src/services/BackgroundTaskService';
import { ThemeProvider } from '../src/theme/ThemeContext';

function AppLayout() {
  const theme = usePaperTheme();

  useEffect(() => {
    registerBackgroundFetchAsync().catch(err => 
      console.error("Failed to register background fetch", err)
    );
  }, []);

  return (
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
  );
}

export default function RootLayout() {
  return (
    <ThemeProvider>
      <AppLayout />
    </ThemeProvider>
  );
}
