import { storageService, TTSSettings } from './StorageService';
import { TTSPlaybackController, TTSState, TTSPlaybackConfig } from './tts/TTSPlaybackController';
import { clearTtsState, setTtsState } from './ForegroundServiceCoordinator';

export type { TTSState } from './tts/TTSPlaybackController';

export const TTS_STATE_EVENTS = {
    STATE_CHANGED: 'tts-state-changed',
};

class TTSStateManager {
    private static instance: TTSStateManager;
    private controller: TTSPlaybackController | null = null;
    private _settings: TTSSettings = { pitch: 1.0, rate: 1.0, chunkSize: 500 };

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

    private emitStateChange(state: TTSState) {
        const { DeviceEventEmitter } = require('react-native');
        DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, state);
    }

    private async updateForegroundNotification(isPlaying: boolean, body: string) {
        const state = this.getState();
        if (!state) return;
        await setTtsState({
            title: state.title,
            body,
            isPlaying,
        });
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

    public async start(chunks: string[], title: string = 'Reading') {
        if (!chunks || chunks.length === 0) return;

        if (!this.controller) {
            this.initializeController(chunks, title);
        }

        this.controller?.start(chunks, title);

        const state = this.getState();
        if (state) {
            await this.updateForegroundNotification(true, `Reading chunk 1 / ${state.chunks.length}`);
        }
    }

    public async stop() {
        await this.controller?.stop();
        await clearTtsState();
    }

    public async pause() {
        await this.controller?.pause();

        const state = this.getState();
        if (state) {
            await this.updateForegroundNotification(
                false,
                `Paused: Chunk ${state.currentChunkIndex + 1}`
            );
        }
    }

    public async resume() {
        this.controller?.resume();
        const state = this.getState();
        if (state) {
            await this.updateForegroundNotification(
                true,
                `Reading chunk ${state.currentChunkIndex + 1} / ${state.chunks.length}`
            );
        }
    }

    public async playPause() {
        await this.controller?.playPause();
        const state = this.getState();
        if (state) {
            const isPlaying = state.isSpeaking && !state.isPaused;
            const msg = isPlaying
                ? `Reading chunk ${state.currentChunkIndex + 1} / ${state.chunks.length}`
                : `Paused: Chunk ${state.currentChunkIndex + 1}`;
            await this.updateForegroundNotification(isPlaying, msg);
        }
    }

    public async next() {
        await this.controller?.next();

        const state = this.getState();
        if (state) {
            const msg = `Reading chunk ${state.currentChunkIndex + 1} / ${state.chunks.length}`;
            await this.updateForegroundNotification(true, msg);
        }
    }

    public async previous() {
        await this.controller?.previous();

        const state = this.getState();
        if (state) {
            const msg = `Reading chunk ${state.currentChunkIndex + 1} / ${state.chunks.length}`;
            await this.updateForegroundNotification(true, msg);
        }
    }

    private initializeController(chunks: string[], title: string) {
        const config: TTSPlaybackConfig = {
            onStateChange: (state: TTSState) => {
                this.emitStateChange(state);
            },
            onChunkChange: async (currentIndex: number) => {
                const state = this.getState();
                if (state) {
                    const msg = `Reading chunk ${currentIndex + 1} / ${state.chunks.length}`;
                    await this.updateForegroundNotification(true, msg);
                }
            },
            onFinish: null,
        };

        this.controller = new TTSPlaybackController(chunks, title, this._settings, config);
    }
}

export const ttsStateManager = TTSStateManager.getInstance();
