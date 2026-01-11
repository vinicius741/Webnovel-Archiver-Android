import * as FileSystem from 'expo-file-system';
// We need to use FileSystem.StorageAccessFramework and FileSystem.writeAsStringAsync
// The helper classes File/Directory/Paths must be imported from the library if they exist, or they are custom?
// Based on previous file content, they were imported from 'expo-file-system'.
// If lint says they exist, we keep them. If not, we found where they came from.
// Step 23 showed `import { Paths, File, Directory } from 'expo-file-system';`
// Lint in step 105 did NOT complain about Paths, File, Directory. It complained about `StorageAccessFramework`, `writeAsStringAsync`, `EncodingType` NOT being there.
// So:
import { Paths, File, Directory } from 'expo-file-system';
import { StorageAccessFramework, EncodingType, writeAsStringAsync } from 'expo-file-system/legacy';
// Wait, `File` and `Directory` are NOT standard expo-file-system exports. They are likely from a wrapper I wrote?
// No, I see `import { Paths, File, Directory } from 'expo-file-system'` in the code I read in Step 23.
// Wait, if `File` and `Directory` were there in Step 23, they might be from a specific library version or I misread?
// Let's re-read Step 23 carefully.
// "import { Paths, File, Directory } from 'expo-file-system';"
// If Step 23 showed that, then `expo-file-system` v19 might have obj-oriented API `File`, `Directory`.
// But lint error said "Module 'expo-file-system' has no exported member 'StorageAccessFramework'".
// Maybe `File` covers it?
// Let's rely on `FileSystem` namespace for SAF.


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

export const clearAllFiles = async (): Promise<void> => {
    const baseDir = new Directory(Paths.document, BASE_DIR_NAME);
    if (baseDir.exists) {
        await baseDir.delete();
        console.log(`[Storage] Deleted base directory: ${BASE_DIR_NAME}`);
    }
};

export const saveEpub = async (filename: string, base64: string): Promise<string> => {
    // 1. Request permissions to access the logical "Downloads" folder or let user pick.
    // In Android 11+ we can't just access a path string. We must use SAF.

    // Check if we can use SAF
    const permissions = await StorageAccessFramework.requestDirectoryPermissionsAsync();

    if (!permissions.granted) {
        throw new Error('Permission to save to Downloads was denied');
    }

    const directoryUri = permissions.directoryUri;

    try {
        // 2. Create the file
        // MIME type for EPUB is application/epub+zip
        const createdFileUri = await StorageAccessFramework.createFileAsync(
            directoryUri,
            filename,
            'application/epub+zip'
        );

        // 3. Write data
        // @ts-ignore: writeAsStringAsync
        await writeAsStringAsync(createdFileUri, base64, { encoding: EncodingType.Base64 });

        console.log(`[Storage] Saved EPUB via SAF: ${createdFileUri}`);
        return createdFileUri;

    } catch (e) {
        console.error('Error saving with SAF', e);
        throw e;
    }
};

export const readChapterFile = async (uri: string): Promise<string> => {
    try {
        const file = new File(uri); // Try from URI
        if (file.exists) {
            return await file.text();
        }
    } catch (e) {
        console.warn('Error reading chapter file', e);
    }
    return '';
};

export const checkFileExists = async (uri: string): Promise<boolean> => {
    try {
        const file = new File(uri);
        return file.exists;
    } catch (e) {
        return false;
    }
};

