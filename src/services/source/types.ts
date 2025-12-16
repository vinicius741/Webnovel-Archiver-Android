export interface NovelMetadata {
    title: string;
    author: string;
    coverUrl?: string;
    description?: string;
    tags?: string[];
    score?: string;
}

export interface ChapterInfo {
    title: string;
    url: string;
    chapterNumber?: number;
}

export interface SourceProvider {
    name: string;
    baseUrl: string;
    
    isSource(url: string): boolean;
    getStoryId(url: string): string;
    parseMetadata(html: string): NovelMetadata;
    getChapterList(html: string, url: string, onProgress?: (message: string) => void): Promise<ChapterInfo[]>;
    parseChapterContent(html: string): string;
}
