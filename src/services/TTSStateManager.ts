import { DeviceEventEmitter, Platform } from 'react-native';
import * as Speech from 'expo-speech';
import { storageService, TTSSettings } from './StorageService';

// Events emitted for React components to sync UI
export const TTS_STATE_EVENTS = {
    STATE_CHANGED: 'tts-state-changed',
};

export interface TTSState {
    isSpeaking: boolean;
    isPaused: boolean;
    chunks: string[];
    currentChunkIndex: number;
    title: string;
}

/**
 * Singleton class that manages TTS state and playback.
 * 
 * This exists separately from the useTTS hook so that background event handlers
 * (e.g., notification button presses via Notifee) can directly control TTS
 * without needing the React component tree to be active.
 */
class TTSStateManager {
    private static instance: TTSStateManager;

    private _isSpeaking = false;
    private _isPaused = false;
    private _chunks: string[] = [];
    private _currentChunkIndex = 0;
    private _title = 'Reading';
    private _settings: TTSSettings = { pitch: 1.0, rate: 1.0, chunkSize: 500 };
    private _onFinishCallback: (() => void) | null = null;

    // Lazy-loaded notification service
    private notificationService: any = null;

    private constructor() {
        this.loadSettings();
    }

    public static getInstance(): TTSStateManager {
        if (!TTSStateManager.instance) {
            TTSStateManager.instance = new TTSStateManager();
        }
        return TTSStateManager.instance;
    }

    private async loadSettings() {
        const settings = await storageService.getTTSSettings();
        if (settings) {
            this._settings = settings;
        }
    }

    private getNotificationService() {
        if (!this.notificationService) {
            // Lazy load to avoid circular dependencies
            const { ttsNotificationService } = require('./TTSNotificationService');
            this.notificationService = ttsNotificationService;
        }
        return this.notificationService;
    }

    private emitStateChange() {
        DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, this.getState());
    }

    public getState(): TTSState {
        return {
            isSpeaking: this._isSpeaking,
            isPaused: this._isPaused,
            chunks: this._chunks,
            currentChunkIndex: this._currentChunkIndex,
            title: this._title,
        };
    }

    public getSettings(): TTSSettings {
        return this._settings;
    }

    public async updateSettings(newSettings: TTSSettings) {
        this._settings = newSettings;
        await storageService.saveTTSSettings(newSettings);
    }

    public setOnFinishCallback(callback: (() => void) | null) {
        this._onFinishCallback = callback;
    }

    /**
     * Start speaking from the beginning of the provided chunks.
     */
    public start(chunks: string[], title: string = 'Reading') {
        if (!chunks || chunks.length === 0) return;

        this._chunks = chunks;
        this._title = title;
        this._currentChunkIndex = 0;
        this._isSpeaking = true;
        this._isPaused = false;

        this.speakCurrentChunk();
        this.emitStateChange();
    }

    /**
     * Stop TTS completely and reset state.
     */
    public async stop() {
        await Speech.stop();
        this._isSpeaking = false;
        this._isPaused = false;
        this._currentChunkIndex = 0;
        this._chunks = [];

        this.getNotificationService().stopService();
        this.emitStateChange();
    }

    /**
     * Pause current playback.
     */
    public async pause() {
        if (!this._isSpeaking || this._isPaused) return;

        await Speech.stop();
        this._isPaused = true;

        this.getNotificationService().updateNotification(
            false,
            this._title,
            `Paused: Chunk ${this._currentChunkIndex + 1}`
        );
        this.emitStateChange();
    }

    /**
     * Resume from paused state.
     */
    public resume() {
        if (!this._isPaused) return;

        this._isPaused = false;
        this.speakCurrentChunk();
        this.emitStateChange();
    }

    /**
     * Toggle between play and pause.
     */
    public async playPause() {
        if (this._isPaused) {
            this.resume();
        } else if (this._isSpeaking) {
            await this.pause();
        }
    }

    /**
     * Skip to next chunk.
     */
    public async next() {
        if (!this._isSpeaking) return;

        await Speech.stop();
        this._isPaused = false;
        this._currentChunkIndex = Math.min(this._currentChunkIndex + 1, this._chunks.length - 1);
        this.speakCurrentChunk();
        this.emitStateChange();
    }

    /**
     * Go to previous chunk.
     */
    public async previous() {
        if (!this._isSpeaking) return;

        await Speech.stop();
        this._isPaused = false;
        this._currentChunkIndex = Math.max(0, this._currentChunkIndex - 1);
        this.speakCurrentChunk();
        this.emitStateChange();
    }

    private speakCurrentChunk() {
        if (this._currentChunkIndex >= this._chunks.length) {
            // Finished all chunks
            this.stop();
            this._onFinishCallback?.();
            return;
        }

        const chunk = this._chunks[this._currentChunkIndex];
        const msg = `Reading chunk ${this._currentChunkIndex + 1} / ${this._chunks.length}`;

        // Update or start notification
        if (this._isSpeaking) {
            this.getNotificationService().updateNotification(true, this._title, msg);
        } else {
            this.getNotificationService().startService(this._title, msg);
            this._isSpeaking = true;
        }

        Speech.speak(chunk, {
            pitch: this._settings.pitch,
            rate: this._settings.rate,
            voice: this._settings.voiceIdentifier,
            onDone: () => {
                // Move to next chunk automatically
                this._currentChunkIndex++;
                this.speakCurrentChunk();
                this.emitStateChange();
            },
            onError: (error) => {
                console.error('[TTSStateManager] Speech error:', error);
                this.stop();
            },
        });
    }
}

export const ttsStateManager = TTSStateManager.getInstance();
