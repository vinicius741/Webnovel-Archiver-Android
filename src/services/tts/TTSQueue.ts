import * as Speech from 'expo-speech';
import { TTSSettings } from '../StorageService';

export interface TTSQueueConfig {
    onChunkStart: (index: number) => void;
    onChunkComplete: (index: number) => void;
    onError: (error: any) => void;
}

export class TTSQueue {
    private chunks: string[] = [];
    private settings: TTSSettings;
    private config: TTSQueueConfig;
    private speakToken: number = 0;
    private activeChunkIndex: number | null = null;

    constructor(chunks: string[], settings: TTSSettings, config: TTSQueueConfig) {
        this.chunks = chunks;
        this.settings = settings;
        this.config = config;
        this.speakToken = 0;
    }

    public updateSettings(newSettings: TTSSettings): void {
        this.settings = newSettings;
    }

    public updateChunks(newChunks: string[]): void {
        this.chunks = newChunks;
        this.invalidate();
    }

    public playChunk(index: number): void {
        if (index < 0 || index >= this.chunks.length) return;

        const currentToken = ++this.speakToken;
        const chunk = this.chunks[index];
        this.activeChunkIndex = index;

        Speech.speak(chunk, {
            pitch: this.settings.pitch,
            rate: this.settings.rate,
            voice: this.settings.voiceIdentifier,
            onStart: () => {
                if (this.speakToken !== currentToken) return;
                this.config.onChunkStart(index);
            },
            onDone: () => {
                if (this.speakToken !== currentToken) return;
                this.activeChunkIndex = null;
                this.config.onChunkComplete(index);
            },
            onError: (error) => {
                if (this.speakToken !== currentToken) return;
                this.activeChunkIndex = null;
                this.config.onError(error);
            },
        });
    }

    public async stop(): Promise<void> {
        this.invalidate();
        await Speech.stop();
    }

    public invalidate(): void {
        this.speakToken++;
        this.activeChunkIndex = null;
    }

    public getActiveChunkIndex(): number | null {
        return this.activeChunkIndex;
    }

    public getLength(): number {
        return this.chunks.length;
    }
}
