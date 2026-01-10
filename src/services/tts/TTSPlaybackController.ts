import * as Speech from 'expo-speech';
import { DeviceEventEmitter } from 'react-native';
import { TTSSettings } from '../StorageService';
import { TTSQueue, TTSQueueConfig } from './TTSQueue';

export enum TTSPlaybackState {
    Idle = 'idle',
    Playing = 'playing',
    Paused = 'paused',
    Stopped = 'stopped',
}

export interface TTSPlaybackConfig {
    onStateChange: (state: TTSState) => void;
    onChunkChange: (currentIndex: number) => void;
    onFinish?: (() => void) | null;
}

export interface TTSState {
    isSpeaking: boolean;
    isPaused: boolean;
    chunks: string[];
    currentChunkIndex: number;
    title: string;
}

export class TTSPlaybackController {
    private state: TTSState;
    private config: TTSPlaybackConfig;
    private settings: TTSSettings;
    private sessionId: number = 0;
    private queue: TTSQueue | null = null;

    constructor(
        chunks: string[],
        title: string,
        settings: TTSSettings,
        config: TTSPlaybackConfig
    ) {
        this.state = {
            isSpeaking: false,
            isPaused: false,
            chunks,
            currentChunkIndex: 0,
            title,
        };
        this.config = config;
        this.settings = settings;
        this.sessionId = 0;
    }

    public getState(): TTSState {
        return { ...this.state };
    }

    public getSettings(): TTSSettings {
        return { ...this.settings };
    }

    public updateSettings(newSettings: TTSSettings): void {
        this.settings = newSettings;
        if (this.queue) {
            this.queue.updateSettings(newSettings);
        }
    }

    public setOnFinishCallback(callback: (() => void) | null): void {
        this.config.onFinish = callback;
    }

    public start(chunks: string[], title: string = 'Reading'): void {
        if (!chunks || chunks.length === 0) return;

        this.state.chunks = chunks;
        this.state.title = title;
        this.state.currentChunkIndex = 0;
        this.state.isSpeaking = true;
        this.state.isPaused = false;

        this.sessionId++;
        this.initializeQueue(chunks);
        this.queue?.processQueue(0);

        this.emitStateChange();
    }

    public async stop(): Promise<void> {
        this.sessionId++;
        await Speech.stop();
        this.state.isSpeaking = false;
        this.state.isPaused = false;
        this.state.currentChunkIndex = 0;
        this.state.chunks = [];
        this.queue = null;

        this.emitStateChange();
    }

    public async pause(): Promise<void> {
        if (!this.state.isSpeaking || this.state.isPaused) return;

        this.sessionId++;
        await Speech.stop();
        this.state.isPaused = true;

        if (this.queue) {
            this.queue.resetBuffer(this.state.currentChunkIndex);
        }

        this.emitStateChange();
    }

    public resume(): void {
        if (!this.state.isPaused) return;

        this.state.isPaused = false;
        this.sessionId++;

        if (this.queue) {
            this.queue.resetBuffer(this.state.currentChunkIndex);
            this.queue.processQueue(this.state.currentChunkIndex);
        }

        this.emitStateChange();
    }

    public async playPause(): Promise<void> {
        if (this.state.isPaused) {
            this.resume();
        } else if (this.state.isSpeaking) {
            await this.pause();
        }
    }

    public async next(): Promise<void> {
        if (!this.state.isSpeaking) return;

        this.sessionId++;
        await Speech.stop();
        this.state.isPaused = false;
        this.state.currentChunkIndex = Math.min(
            this.state.currentChunkIndex + 1,
            this.state.chunks.length - 1
        );

        if (this.queue) {
            this.queue.resetBuffer(this.state.currentChunkIndex);
            this.queue.processQueue(this.state.currentChunkIndex);
        }

        this.emitStateChange();
        this.config.onChunkChange(this.state.currentChunkIndex);
    }

    public async previous(): Promise<void> {
        if (!this.state.isSpeaking) return;

        this.sessionId++;
        await Speech.stop();
        this.state.isPaused = false;
        this.state.currentChunkIndex = Math.max(0, this.state.currentChunkIndex - 1);

        if (this.queue) {
            this.queue.resetBuffer(this.state.currentChunkIndex);
            this.queue.processQueue(this.state.currentChunkIndex);
        }

        this.emitStateChange();
        this.config.onChunkChange(this.state.currentChunkIndex);
    }

    public getSessionId(): number {
        return this.sessionId;
    }

    private initializeQueue(chunks: string[]): void {
        const currentSessionId = this.sessionId;
        const queueConfig: TTSQueueConfig = {
            bufferSize: 3,
            onChunkStart: (index: number) => {
                if (this.sessionId !== currentSessionId) return;

                this.state.currentChunkIndex = index;
                this.emitStateChange();
                this.config.onChunkChange(index);
            },
            onChunkComplete: (index: number) => {
                if (this.sessionId !== currentSessionId) return;

                if (index >= chunks.length - 1) {
                    this.stop();
                    this.config.onFinish?.();
                } else {
                    if (this.queue) {
                        this.queue.processQueue(this.state.currentChunkIndex);
                    }
                }
            },
            onError: (error: any) => {
                if (this.sessionId === currentSessionId) {
                    console.error('[TTSPlaybackController] Speech error:', error);
                    this.stop();
                }
            },
        };

        this.queue = new TTSQueue(chunks, this.settings, queueConfig);
    }

    private emitStateChange(): void {
        this.config.onStateChange(this.getState());
    }
}
