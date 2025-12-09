export interface Chapter {
    id: string;
    title: string;
    url: string;
    content?: string; // HTML content, loaded only when needed? Or maybe path to file?
    // Ideally content is stored in file, not here. Here we track metadata.
    filePath?: string; // Path to local HTML file
    downloaded?: boolean;
}

export const DownloadStatus = {
    Idle: 'idle',
    Downloading: 'downloading',
    Completed: 'completed',
    Failed: 'failed',
    Paused: 'paused',
    Partial: 'partial',
} as const;

export type DownloadStatus = typeof DownloadStatus[keyof typeof DownloadStatus];

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
    dateAdded?: number; // timestamp for initial addition
}
