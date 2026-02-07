import { useEffect, useRef } from 'react';
import { AppState, AppStateStatus } from 'react-native';
import { Stack, usePathname, useRouter } from 'expo-router';
import { useTheme as usePaperTheme } from 'react-native-paper';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { notificationService } from '../src/services/NotificationService';
import { ThemeProvider } from '../src/theme/ThemeContext';
import { AlertProvider } from '../src/context/AlertContext';
import { backgroundServiceInitializer } from '../src/services/BackgroundService';
import { ttsLifecycleService } from '../src/services/TTSLifecycleService';
import { ttsStateManager } from '../src/services/TTSStateManager';
import { TTS_RELIABILITY_V2 } from '../src/services/TTSFeatureFlags';

function ReaderResumeCoordinator() {
  const router = useRouter();
  const pathname = usePathname();
  const appStateRef = useRef<AppStateStatus>(AppState.currentState);
  const pathnameRef = useRef(pathname);

  useEffect(() => {
    pathnameRef.current = pathname;
  }, [pathname]);

  useEffect(() => {
    if (!TTS_RELIABILITY_V2) return;

    const maybeResumeReader = async () => {
      const session = await ttsStateManager.getPersistedSession();
      if (!session) return;
      if (!session.storyId || !session.chapterId) return;
      if (!session.wasPlaying && !session.isPaused) return;

      const currentPath = pathnameRef.current || '';
      const match = currentPath.match(/^\/reader\/([^/]+)\/([^/?#]+)/);
      const isAlreadyOnReader = (() => {
        if (!match) return false;
        const activeStoryId = match[1];
        const activeChapterId = match[2];
        try {
          return activeStoryId === session.storyId && decodeURIComponent(activeChapterId) === session.chapterId;
        } catch {
          return false;
        }
      })();
      if (isAlreadyOnReader) return;

      router.replace(`/reader/${session.storyId}/${encodeURIComponent(session.chapterId)}?resumeSession=true`);
    };

    const subscription = AppState.addEventListener('change', (nextState) => {
      const prev = appStateRef.current;
      appStateRef.current = nextState;

      if ((prev === 'inactive' || prev === 'background') && nextState === 'active') {
        void maybeResumeReader();
      }
    });

    void maybeResumeReader();

    return () => {
      subscription.remove();
    };
  }, [router]);

  return null;
}

function AppLayout() {
  const theme = usePaperTheme();

  useEffect(() => {
    // Request notification permissions
    notificationService.requestPermissions();
    // Ensure background service initializer is called
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const _ = backgroundServiceInitializer;

    if (TTS_RELIABILITY_V2) {
      ttsLifecycleService.start();
    }

    return () => {
      if (TTS_RELIABILITY_V2) {
        ttsLifecycleService.stop();
      }
    };
  }, []);

  return (
    <>
      <ReaderResumeCoordinator />
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
    </>
  );
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <ThemeProvider>
        <AlertProvider>
          <AppLayout />
        </AlertProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  );
}
