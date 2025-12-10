import { useEffect } from 'react';
import { Stack } from 'expo-router';
import { useTheme as usePaperTheme } from 'react-native-paper';
import { notificationService } from '../src/services/NotificationService';
import { ThemeProvider } from '../src/theme/ThemeContext';
import { AlertProvider } from '../src/context/AlertContext';
import { backgroundServiceInitializer } from '../src/services/BackgroundService';

function AppLayout() {
  const theme = usePaperTheme();

  useEffect(() => {
    // Request notification permissions
    notificationService.requestPermissions();
    // Ensure background service initializer is called
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const _ = backgroundServiceInitializer;
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
      <AlertProvider>
        <AppLayout />
      </AlertProvider>
    </ThemeProvider>
  );
}
