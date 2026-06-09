import { Paths, File, Directory } from "expo-file-system";
import { EncodingType, writeAsStringAsync } from "expo-file-system/legacy";

import { Story } from "../../types";

const BASE_DIR_NAME = "novels";
const EPUB_DIR_NAME = "epubs";

const getChapterFilename = (chapterIndex: number, title: string): string => {
  const safeTitle = title.replace(/[^a-z0-9]/gi, "_").toLowerCase();
  return `${chapterIndex.toString().padStart(4, "0")}_${safeTitle}.html`;
};

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

export const saveChapter = async (
  novelId: string,
  chapterIndex: number,
  title: string,
  content: string,
): Promise<string> => {
  const novelDir = ensureNovelDirExists(novelId);
  const filename = getChapterFilename(chapterIndex, title);

  const file = new File(novelDir, filename);
  file.write(content);
  console.log(`[Storage] Saved chapter: ${file.uri}`);
  return file.uri;
};

export const copyChapterToNovel = async (
  sourceUri: string,
  novelId: string,
  chapterIndex: number,
  title: string,
): Promise<string> => {
  const novelDir = ensureNovelDirExists(novelId);
  const filename = getChapterFilename(chapterIndex, title);
  const sourceFile = new File(sourceUri);
  const destinationFile = new File(novelDir, filename);
  sourceFile.copy(destinationFile);
  console.log(`[Storage] Copied chapter to archive: ${destinationFile.uri}`);
  return destinationFile.uri;
};

export const saveMetadata = async (novelId: string, metadata: Story) => {
  const novelDir = ensureNovelDirExists(novelId);
  const file = new File(novelDir, "metadata.json");
  file.write(JSON.stringify(metadata, null, 2));
  console.log(`[Storage] Saved metadata: ${file.uri}`);
};

export const listNovelFiles = async (novelId: string) => {
  const novelDir = new Directory(Paths.document, BASE_DIR_NAME, novelId);
  if (!novelDir.exists) return [];
  return novelDir.list().map((item) => item.name);
};

export const deleteNovel = async (novelId: string): Promise<void> => {
  const novelDir = new Directory(Paths.document, BASE_DIR_NAME, novelId);
  if (novelDir.exists) {
    novelDir.delete();
    console.log(`[Storage] Deleted novel directory: ${novelId}`);
  }
};

export const clearAllFiles = async (): Promise<void> => {
  const baseDir = new Directory(Paths.document, BASE_DIR_NAME);
  if (baseDir.exists) {
    baseDir.delete();
    console.log(`[Storage] Deleted base directory: ${BASE_DIR_NAME}`);
  }
};

export const saveEpub = async (
  novelId: string,
  filename: string,
  base64: string,
): Promise<string> => {
  try {
    const novelDir = ensureNovelDirExists(novelId);
    const epubDir = new Directory(novelDir, EPUB_DIR_NAME);
    if (!epubDir.exists) {
      epubDir.create();
    }

    const file = new File(epubDir, filename);
    await writeAsStringAsync(file.uri, base64, {
      encoding: EncodingType.Base64,
    });

    console.log(`[Storage] Saved EPUB: ${file.uri}`);
    return file.uri;
  } catch (e) {
    console.error("Error saving EPUB", e);
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
    console.warn("Error reading chapter file", e);
  }
  return "";
};

export const writeChapterFile = async (
  uri: string,
  content: string,
): Promise<void> => {
  const file = new File(uri);
  file.write(content);
};

export const checkFileExists = async (uri: string): Promise<boolean> => {
  try {
    const file = new File(uri);
    return file.exists;
  } catch {
    return false;
  }
};
