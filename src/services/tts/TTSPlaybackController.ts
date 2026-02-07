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

export interface TTSStartOptions {
    startChunkIndex?: number;
    startPaused?: boolean;
}

export class TTSPlaybackController {
    private state: TTSState;
    private config: TTSPlaybackConfig;
    private settings: TTSSettings;
    private sessionId: number = 0;
    private queue: TTSQueue | null = null;
    private userOnFinishCallback: (() => void) | null = null;

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
    }

    public getState(): TTSState {
        return { ...this.state };
    }

    public getSettings(): TTSSettings {
        return { ...this.settings };
    }

    public updateSettings(newSettings: TTSSettings): void {
        this.settings = newSettings;
        this.queue?.updateSettings(newSettings);
    }

    public setOnFinishCallback(callback: (() => void) | null): void {
        this.userOnFinishCallback = callback;
    }

    public start(chunks: string[], title: string = 'Reading', options?: TTSStartOptions): void {
        if (!chunks || chunks.length === 0) return;

        const startChunkIndex = Math.max(0, Math.min(options?.startChunkIndex ?? 0, chunks.length - 1));
        const startPaused = options?.startPaused ?? false;

        this.state.chunks = chunks;
        this.state.title = title;
        this.state.currentChunkIndex = startChunkIndex;
        this.state.isSpeaking = true;
        this.state.isPaused = startPaused;

        this.sessionId++;
        this.initializeQueue(chunks);
        this.emitStateChange();
        this.config.onChunkChange(startChunkIndex);

        if (!startPaused) {
            this.queue?.playChunk(startChunkIndex);
        }
    }

    public async stop(): Promise<void> {
        this.sessionId++;
        await this.queue?.stop();
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
        this.state.isPaused = true;
        await this.queue?.stop();
        this.emitStateChange();
    }

    public resume(): void {
        if (!this.state.isPaused || !this.state.isSpeaking) return;
        this.sessionId++;
        this.state.isPaused = false;
        this.emitStateChange();
        this.queue?.playChunk(this.state.currentChunkIndex);
    }

    public async playPause(): Promise<void> {
        if (this.state.isPaused) {
            this.resume();
        } else if (this.state.isSpeaking) {
            await this.pause();
        }
    }

    public async next(): Promise<void> {
        if (!this.state.isSpeaking || this.state.chunks.length === 0) return;

        this.sessionId++;
        const nextIndex = Math.min(this.state.currentChunkIndex + 1, this.state.chunks.length - 1);
        this.state.currentChunkIndex = nextIndex;
        this.state.isPaused = false;
        await this.queue?.stop();
        this.emitStateChange();
        this.config.onChunkChange(nextIndex);
        this.queue?.playChunk(nextIndex);
    }

    public async previous(): Promise<void> {
        if (!this.state.isSpeaking || this.state.chunks.length === 0) return;

        this.sessionId++;
        const prevIndex = Math.max(0, this.state.currentChunkIndex - 1);
        this.state.currentChunkIndex = prevIndex;
        this.state.isPaused = false;
        await this.queue?.stop();
        this.emitStateChange();
        this.config.onChunkChange(prevIndex);
        this.queue?.playChunk(prevIndex);
    }

    public async restartCurrentChunk(): Promise<void> {
        if (!this.state.isSpeaking || this.state.isPaused) return;
        this.sessionId++;
        await this.queue?.stop();
        this.queue?.playChunk(this.state.currentChunkIndex);
    }

    public getSessionId(): number {
        return this.sessionId;
    }

    private initializeQueue(chunks: string[]): void {
        const sessionAtInit = this.sessionId;
        const queueConfig: TTSQueueConfig = {
            onChunkStart: (index: number) => {
                if (this.sessionId !== sessionAtInit) return;
                this.state.currentChunkIndex = index;
                this.emitStateChange();
                this.config.onChunkChange(index);
            },
            onChunkComplete: (index: number) => {
                if (this.sessionId !== sessionAtInit) return;
                if (!this.state.isSpeaking || this.state.isPaused) return;

                if (index >= chunks.length - 1) {
                    this.config.onFinish?.();
                    this.userOnFinishCallback?.();
                    void this.stop();
                    return;
                }

                const nextIndex = index + 1;
                this.state.currentChunkIndex = nextIndex;
                this.emitStateChange();
                this.config.onChunkChange(nextIndex);
                this.queue?.playChunk(nextIndex);
            },
            onError: (error: any) => {
                if (this.sessionId !== sessionAtInit) return;
                console.error('[TTSPlaybackController] Speech error:', error);
                void this.stop();
            },
        };

        this.queue = new TTSQueue(chunks, this.settings, queueConfig);
    }

    private emitStateChange(): void {
        this.config.onStateChange(this.getState());
    }
}
