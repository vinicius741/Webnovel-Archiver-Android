import * as Speech from 'expo-speech';
import { TTSSettings } from '../StorageService';

export interface TTSQueueConfig {
    bufferSize?: number;
    sessionId: number;
    onChunkStart: (index: number) => void;
    onChunkComplete: (index: number) => void;
    onError: (error: any) => void;
}

export class TTSQueue {
    private chunks: string[] = [];
    private settings: TTSSettings;
    private config: TTSQueueConfig;
    private bufferedIndex: number = 0;

    constructor(chunks: string[], settings: TTSSettings, config: TTSQueueConfig) {
        this.chunks = chunks;
        this.settings = settings;
        this.config = config;
        this.bufferedIndex = 0;
    }

    public updateSettings(newSettings: TTSSettings): void {
        this.settings = newSettings;
    }

    public updateChunks(newChunks: string[]): void {
        this.chunks = newChunks;
        this.bufferedIndex = 0;
    }

    public processQueue(currentIndex: number): void {
        const bufferSize = this.config.bufferSize || 3;

        while (
            this.bufferedIndex < this.chunks.length &&
            this.bufferedIndex < currentIndex + bufferSize
        ) {
            const index = this.bufferedIndex;
            const chunk = this.chunks[index];
            this.bufferedIndex++;

            Speech.speak(chunk, {
                pitch: this.settings.pitch,
                rate: this.settings.rate,
                voice: this.settings.voiceIdentifier,
                onStart: () => {
                    if (this.config.sessionId !== this.config.sessionId) return;
                    this.config.onChunkStart(index);
                },
                onDone: () => {
                    if (this.config.sessionId !== this.config.sessionId) return;
                    this.config.onChunkComplete(index);
                },
                onError: (error) => {
                    if (this.config.sessionId !== this.config.sessionId) return;
                    this.config.onError(error);
                },
            });
        }
    }

    public resetBuffer(currentIndex: number): void {
        this.bufferedIndex = currentIndex;
    }

    public getBufferedIndex(): number {
        return this.bufferedIndex;
    }

    public getLength(): number {
        return this.chunks.length;
    }
}
