import { EventEmitter, type EventSubscription, requireOptionalNativeModule } from 'expo-modules-core';

export type MediaButtonAction = 'playPause';
export type MediaButtonEvent = { action: MediaButtonAction };

export type TtsMediaSessionPayload = {
  title: string;
  body: string;
  isPlaying: boolean;
  storyId?: string;
  chapterId?: string;
};

type NativeTtsMediaSessionModule = {
  startSession: (title: string, body: string, isPlaying: boolean, storyId?: string, chapterId?: string) => Promise<void>;
  updateSession: (title: string, body: string, isPlaying: boolean, storyId?: string, chapterId?: string) => Promise<void>;
  stopSession: () => Promise<void>;
};

const nativeModule = requireOptionalNativeModule<NativeTtsMediaSessionModule>('TtsMediaSession');
const emitter = nativeModule ? new EventEmitter(nativeModule as any) : null;

export function isAvailable(): boolean {
  return !!nativeModule;
}

export function startSession(payload: TtsMediaSessionPayload): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.startSession(
    payload.title,
    payload.body,
    payload.isPlaying,
    payload.storyId,
    payload.chapterId
  );
}

export function updateSession(payload: TtsMediaSessionPayload): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.updateSession(
    payload.title,
    payload.body,
    payload.isPlaying,
    payload.storyId,
    payload.chapterId
  );
}

export function stopSession(): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.stopSession();
}

export function addMediaButtonListener(
  listener: (event: MediaButtonEvent) => void
): EventSubscription | null {
  if (!emitter) return null;
  return emitter.addListener('onMediaButton', listener as any);
}
