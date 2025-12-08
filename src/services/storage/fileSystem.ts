import { Paths, File, Directory } from 'expo-file-system';

const BASE_DIR_NAME = 'novels';

export const ensureNovelDirExists = (novelId: string): Directory => {
    const baseDir = new Directory(Paths.document, BASE_DIR_NAME);
    if (!baseDir.exists) {
        baseDir.create();
    }

    const novelDir = new Directory(baseDir, novelId);
    if (!novelDir.exists) {
        novelDir.create();
    }
    return novelDir;
};

export const saveChapter = async (novelId: string, chapterIndex: number, title: string, content: string): Promise<string> => {
    const novelDir = ensureNovelDirExists(novelId);

    // Sanitize title for filename
    const safeTitle = title.replace(/[^a-z0-9]/gi, '_').toLowerCase();
    const filename = `${chapterIndex.toString().padStart(4, '0')}_${safeTitle}.html`;

    const file = new File(novelDir, filename);
    file.write(content);
    console.log(`[Storage] Saved chapter: ${file.uri}`);
    return file.uri;
};

export const saveMetadata = async (novelId: string, metadata: any) => {
    const novelDir = ensureNovelDirExists(novelId);
    const file = new File(novelDir, 'metadata.json');
    file.write(JSON.stringify(metadata, null, 2));
    console.log(`[Storage] Saved metadata: ${file.uri}`);
};

export const listNovelFiles = async (novelId: string) => {
    const novelDir = new Directory(Paths.document, BASE_DIR_NAME, novelId);
    if (!novelDir.exists) return [];
    return novelDir.list().map(item => item.name);
};

export const deleteNovel = async (novelId: string): Promise<void> => {
    const novelDir = new Directory(Paths.document, BASE_DIR_NAME, novelId);
    if (novelDir.exists) {
        await novelDir.delete();
        console.log(`[Storage] Deleted novel directory: ${novelId}`);
    }
};
