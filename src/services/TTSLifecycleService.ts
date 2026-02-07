import * as Speech from 'expo-speech';
import { AppState, AppStateStatus } from 'react-native';
import { ttsStateManager } from './TTSStateManager';

const WATCHDOG_INTERVAL_MS = 3000;
const SILENT_STOP_THRESHOLD = 2;

class TTSLifecycleService {
    private started = false;
    private appState: AppStateStatus = AppState.currentState;
    private watchdogTimer: ReturnType<typeof setInterval> | null = null;
    private silentStopCount = 0;
    private appStateSubscription: { remove: () => void } | null = null;

    public start() {
        if (this.started) return;
        this.started = true;

        this.appStateSubscription = AppState.addEventListener('change', this.handleAppStateChange);
        if (this.appState === 'active') {
            this.startWatchdog();
            void this.handleForeground();
        }
    }

    public stop() {
        if (!this.started) return;
        this.started = false;
        this.appStateSubscription?.remove();
        this.appStateSubscription = null;
        this.stopWatchdog();
        this.silentStopCount = 0;
    }

    private handleAppStateChange = (nextAppState: AppStateStatus) => {
        const previous = this.appState;
        this.appState = nextAppState;

        if ((previous === 'background' || previous === 'inactive') && nextAppState === 'active') {
            this.startWatchdog();
            void this.handleForeground();
            return;
        }

        if (nextAppState !== 'active') {
            this.stopWatchdog();
            this.silentStopCount = 0;
        }
    };

    private async handleForeground() {
        const session = await ttsStateManager.getPersistedSession();
        if (!session || !session.wasPlaying) return;

        const state = ttsStateManager.getState();
        if (!state || state.isPaused || !state.isSpeaking) return;

        try {
            const isSpeaking = await Speech.isSpeakingAsync();
            if (!isSpeaking) {
                await ttsStateManager.recoverPlaybackFromSilentStop();
            }
        } catch (error) {
            console.warn('[TTSLifecycleService] Foreground reconciliation failed:', error);
        }
    }

    private startWatchdog() {
        if (this.watchdogTimer) return;
        this.watchdogTimer = setInterval(() => {
            void this.runWatchdogTick();
        }, WATCHDOG_INTERVAL_MS);
    }

    private stopWatchdog() {
        if (!this.watchdogTimer) return;
        clearInterval(this.watchdogTimer);
        this.watchdogTimer = null;
    }

    private async runWatchdogTick() {
        if (this.appState !== 'active') return;

        const state = ttsStateManager.getState();
        if (!state || !state.isSpeaking || state.isPaused) {
            this.silentStopCount = 0;
            return;
        }

        try {
            const isEngineSpeaking = await Speech.isSpeakingAsync();
            if (isEngineSpeaking) {
                this.silentStopCount = 0;
                return;
            }

            this.silentStopCount += 1;
            if (this.silentStopCount < SILENT_STOP_THRESHOLD) return;

            this.silentStopCount = 0;
            await ttsStateManager.recoverPlaybackFromSilentStop();
        } catch (error) {
            console.warn('[TTSLifecycleService] Watchdog check failed:', error);
        }
    }
}

export const ttsLifecycleService = new TTSLifecycleService();
