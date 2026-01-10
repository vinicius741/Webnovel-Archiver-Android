import * as DocumentPicker from 'expo-document-picker';
import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { Alert } from 'react-native';
import { storageService } from './StorageService';
import { Story } from '../types';

export interface BackupData {
    version: number;
    exportDate: string;
    library: Story[];
}

const BACKUP_VERSION = 1;

class BackupService {
    async exportBackup(): Promise<{ success: boolean; message: string }> {
        try {
            const library = await storageService.getLibrary();

            if (library.length === 0) {
                return { success: false, message: 'Your library is empty' };
            }

            const backupData: BackupData = {
                version: BACKUP_VERSION,
                exportDate: new Date().toISOString(),
                library: library.map(story => ({
                    ...story,
                    content: undefined,
                    filePath: undefined,
                    epubPath: undefined,
                    chapters: story.chapters.map(chapter => ({
                        ...chapter,
                        content: undefined,
                        filePath: undefined,
                        downloaded: false,
                    })),
                })),
            };

            const json = JSON.stringify(backupData, null, 2);

            const jsonSizeInMB = json.length / (1024 * 1024);
            if (jsonSizeInMB > 50) {
                return {
                    success: false,
                    message: `Backup is too large (${jsonSizeInMB.toFixed(1)} MB). Consider exporting fewer novels.`,
                };
            }

            const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, -5);
            const filename = `webnovel_backup_${timestamp}.json`;

            const file = new File(Paths.cache, filename);
            if (!file.exists) {
                file.create();
            }
            file.write(json);

            if (await Sharing.isAvailableAsync()) {
                await Sharing.shareAsync(file.uri, {
                    mimeType: 'application/json',
                    dialogTitle: 'Export Backup',
                    UTI: 'public.json',
                });
                return { success: true, message: 'Backup exported successfully' };
            } else {
                return { success: false, message: 'Sharing is not available on this device' };
            }
        } catch (error) {
            console.error('Backup export failed', error);
            const errorMessage = error instanceof Error ? error.message : 'Unknown error';
            return { success: false, message: `Failed to export backup: ${errorMessage}` };
        }
    }

    async importBackup(): Promise<{ success: boolean; message: string; stats?: { added: number; updated: number } }> {
        try {
            const result = await DocumentPicker.getDocumentAsync({
                type: 'application/json',
                copyToCacheDirectory: true,
            });

            if (result.canceled || !result.assets || result.assets.length === 0) {
                return { success: false, message: 'No file selected' };
            }

            const fileUri = result.assets[0].uri;

            const file = new File(fileUri);
            if (!file.exists) {
                return { success: false, message: 'File not found' };
            }

            const content = await file.text();

            let backupData: BackupData;
            try {
                backupData = JSON.parse(content);
            } catch (parseError) {
                return {
                    success: false,
                    message: 'Invalid backup file: not valid JSON',
                };
            }

            if (!backupData.version) {
                return { success: false, message: 'Invalid backup file: missing version' };
            }

            if (!Array.isArray(backupData.library)) {
                return { success: false, message: 'Invalid backup file: missing library' };
            }

            if (!backupData.library.every(story => story && typeof story.id === 'string')) {
                return { success: false, message: 'Invalid backup file: malformed story data' };
            }

            const existingLibrary = await storageService.getLibrary();
            const existingMap = new Map(existingLibrary.map(s => [s.id, s]));

            let addedCount = 0;
            let updatedCount = 0;

            backupData.library.forEach(story => {
                const existingIndex = existingLibrary.findIndex(s => s.id === story.id);
                if (existingIndex !== -1) {
                    const existing = existingLibrary[existingIndex];
                    existingLibrary[existingIndex] = {
                        ...story,
                        downloadedChapters: existing.downloadedChapters,
                        lastUpdated: existing.lastUpdated,
                        lastReadChapterId: existing.lastReadChapterId,
                        dateAdded: existing.dateAdded,
                    };
                    updatedCount++;
                } else {
                    existingLibrary.push(story);
                    addedCount++;
                }
            });

            await storageService.saveLibrary(existingLibrary);

            return {
                success: true,
                message: `Imported ${addedCount + updatedCount} novels (${addedCount} new, ${updatedCount} updated)`,
                stats: { added: addedCount, updated: updatedCount },
            };
        } catch (error) {
            console.error('Backup import failed', error);
            const errorMessage = error instanceof Error ? error.message : 'Unknown error';
            return { success: false, message: `Failed to import backup: ${errorMessage}` };
        }
    }
}

export const backupService = new BackupService();
