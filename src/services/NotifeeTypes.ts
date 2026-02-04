/**
 * Type-safe Notifee loader for React Native apps that may run in environments
 * without Notifee (e.g., Expo Go). This module provides type-safe access to
 * Notifee APIs while gracefully handling environments where it's unavailable.
 */

import type { Module } from '@notifee/react-native/dist/types/Module';
import type { AndroidImportance, AndroidColor, AndroidCategory } from '@notifee/react-native/dist/types/NotificationAndroid';
import type { EventType } from '@notifee/react-native/dist/types/Notification';

/**
 * Type-safe interface for the Notifee module with Android-specific exports
 */
export interface NotifeeModule {
    default: Module;
    AndroidImportance: typeof AndroidImportance;
    AndroidColor: typeof AndroidColor;
    AndroidCategory: typeof AndroidCategory;
    EventType: typeof EventType;
}

/**
 * Result of loading Notifee - either the module or null if unavailable
 */
export type NotifeeLoadResult = NotifeeModule | null;

/**
 * Cached Notifee module reference
 */
let cachedNotifee: NotifeeLoadResult = null;

/**
 * Lazily loads Notifee with type safety.
 * Returns null if Notifee is not available (e.g., in Expo Go).
 *
 * @returns Typed Notifee module or null
 */
export function loadNotifee(): NotifeeLoadResult {
    if (cachedNotifee !== null) {
        return cachedNotifee;
    }

    try {
        // eslint-disable-next-line @typescript-eslint/no-unsafe-member-access
        const notifeeModule = require('@notifee/react-native') as unknown;

        // Validate that the module has the expected structure
        if (
            notifeeModule &&
            typeof notifeeModule === 'object' &&
            'default' in notifeeModule &&
            typeof notifeeModule.default === 'object'
        ) {
            cachedNotifee = notifeeModule as NotifeeModule;
            return cachedNotifee;
        }

        return null;
    } catch {
        // Notifee not available (e.g., Expo Go)
        return null;
    }
}

/**
 * Clears the cached Notifee module. Primarily useful for testing.
 */
export function clearNotifeeCache(): void {
    cachedNotifee = null;
}
