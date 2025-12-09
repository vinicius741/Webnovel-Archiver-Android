import * as BackgroundFetch from 'expo-background-fetch';
import * as TaskManager from 'expo-task-manager';
import { storageService } from './StorageService';

const BACKGROUND_FETCH_TASK = 'background-fetch-library-update';

// 1. Define the task by name
TaskManager.defineTask(BACKGROUND_FETCH_TASK, async () => {
    const now = Date.now();
    console.log(`Got background fetch call at date: ${new Date(now).toISOString()}`);

    try {
        // TODO: Implement actual update logic here
        // For now, we'll just log or maybe refresh the library list if we had an API
        // checking for new chapters involves:
        // 1. Getting all novels from storageService
        // 2. For each novel, fetching the TOC
        // 3. Comparing chapter counts
        // 4. Updating storage if new chapters found

        // const library = await storageService.getLibrary();
        // ... logic ...

        console.log('Background fetch completed');
        // Be sure to return the successful result type!
        return BackgroundFetch.BackgroundFetchResult.NewData;
    } catch (error) {
        console.error('Background fetch failed:', error);
        return BackgroundFetch.BackgroundFetchResult.Failed;
    }
});

// 2. Register the task at some point in your app by providing the same name,
// and some configuration options for how the background fetch should behave
// Note: This does NOT work on Expo Go for Android/iOS in the background efficiently, 
// but does in development builds / production.
export async function registerBackgroundFetchAsync() {
    return BackgroundFetch.registerTaskAsync(BACKGROUND_FETCH_TASK, {
        minimumInterval: 60 * 60 * 6, // 6 hours
        stopOnTerminate: false, // android only,
        startOnBoot: true, // android only
    });
}

// 3. (Optional) Unregister tasks by specifying the task name
// This would usually be called when the user logs out or
// if you want to skip updates
export async function unregisterBackgroundFetchAsync() {
    return BackgroundFetch.unregisterTaskAsync(BACKGROUND_FETCH_TASK);
}
