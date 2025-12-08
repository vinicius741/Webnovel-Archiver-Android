export interface Chapter {
    id: string;
    title: string;
    url: string;
    content?: string; // HTML content, loaded only when needed? Or maybe path to file?
    // Ideally content is stored in file, not here. Here we track metadata.
    filePath?: string; // Path to local HTML file
}

export type DownloadStatus = 'idle' | 'downloading' | 'completed' | 'failed' | 'paused';

export interface Story {
    id: string;
    title: string;
    author: string;
    coverUrl?: string;
    sourceUrl: string;
    status: DownloadStatus;
    totalChapters: number;
    downloadedChapters: number;
    chapters: Chapter[];
    lastUpdated?: number; // timestamp
}
