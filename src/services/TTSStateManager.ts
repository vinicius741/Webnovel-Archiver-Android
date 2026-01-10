import { storageService, TTSSettings } from './StorageService';
import { TTSPlaybackController, TTSState, TTSPlaybackConfig, TTSPlaybackState } from './tts/TTSPlaybackController';

export const TTS_STATE_EVENTS = {
    STATE_CHANGED: 'tts-state-changed',
};

class TTSStateManager {
    private static instance: TTSStateManager;
    private controller: TTSPlaybackController | null = null;
    private _settings: TTSSettings = { pitch: 1.0, rate: 1.0, chunkSize: 500 };
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
            const { ttsNotificationService } = require('./TTSNotificationService');
            this.notificationService = ttsNotificationService;
        }
        return this.notificationService;
    }

    private emitStateChange(state: TTSState) {
        const { DeviceEventEmitter } = require('react-native');
        DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, state);
    }

    public getState(): TTSState | null {
        return this.controller?.getState() || null;
    }

    public getSettings(): TTSSettings {
        return this._settings;
    }

    public async updateSettings(newSettings: TTSSettings) {
        this._settings = newSettings;
        await storageService.saveTTSSettings(newSettings);
        this.controller?.updateSettings(newSettings);
    }

    public setOnFinishCallback(callback: (() => void) | null) {
        this.controller?.setOnFinishCallback(callback);
    }

    public start(chunks: string[], title: string = 'Reading') {
        if (!chunks || chunks.length === 0) return;

        if (!this.controller) {
            this.initializeController(chunks, title);
        }

        this.controller?.start(chunks, title);

        const state = this.getState();
        if (state) {
            this.getNotificationService().startService(
                state.title,
                `Reading chunk 1 / ${state.chunks.length}`
            );
        }
    }

    public async stop() {
        await this.controller?.stop();
        this.getNotificationService().stopService();
    }

    public async pause() {
        await this.controller?.pause();

        const state = this.getState();
        if (state) {
            this.getNotificationService().updateNotification(
                false,
                state.title,
                `Paused: Chunk ${state.currentChunkIndex + 1}`
            );
        }
    }

    public resume() {
        this.controller?.resume();
    }

    public async playPause() {
        await this.controller?.playPause();
    }

    public async next() {
        await this.controller?.next();

        const state = this.getState();
        if (state) {
            const msg = `Reading chunk ${state.currentChunkIndex + 1} / ${state.chunks.length}`;
            this.getNotificationService().updateNotification(true, state.title, msg);
        }
    }

    public async previous() {
        await this.controller?.previous();

        const state = this.getState();
        if (state) {
            const msg = `Reading chunk ${state.currentChunkIndex + 1} / ${state.chunks.length}`;
            this.getNotificationService().updateNotification(true, state.title, msg);
        }
    }

    private initializeController(chunks: string[], title: string) {
        const config: TTSPlaybackConfig = {
            onStateChange: (state: TTSState) => {
                this.emitStateChange(state);
            },
            onChunkChange: (currentIndex: number) => {
                const state = this.getState();
                if (state) {
                    const msg = `Reading chunk ${currentIndex + 1} / ${state.chunks.length}`;
                    this.getNotificationService().updateNotification(true, state.title, msg);
                }
            },
            onFinish: null,
        };

        this.controller = new TTSPlaybackController(chunks, title, this._settings, config);
    }
}

export const ttsStateManager = TTSStateManager.getInstance();
