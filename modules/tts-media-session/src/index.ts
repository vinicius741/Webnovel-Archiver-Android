import {
  EventEmitter,
  type EventSubscription,
  requireOptionalNativeModule,
} from "expo-modules-core";

export type MediaButtonAction = "playPause";
export type MediaButtonEvent = { action: MediaButtonAction };

export type NativeTtsPlaybackStatus =
  | "buffering"
  | "playing"
  | "paused"
  | "stopped"
  | "completed"
  | "error";

export type NativeTtsPlaybackState = {
  status: NativeTtsPlaybackStatus;
  title: string;
  currentIndex: number;
  total: number;
  isPlaying: boolean;
  isPaused: boolean;
  storyId?: string;
  chapterId?: string;
};

export type NativeTtsVoice = {
  identifier: string;
  name: string;
  language: string;
  quality?: string;
  latency?: string;
};

export type TtsPlaybackPayload = {
  units: string[];
  title: string;
  storyId?: string;
  chapterId?: string;
  startIndex?: number;
  pitch: number;
  rate: number;
  voiceIdentifier?: string;
};

type NativeTtsMediaSessionModule = {
  startPlayback: (
    units: string[],
    title: string,
    storyId: string | undefined,
    chapterId: string | undefined,
    startIndex: number,
    pitch: number,
    rate: number,
    voiceIdentifier?: string,
  ) => Promise<void>;
  pausePlayback: () => Promise<void>;
  resumePlayback: () => Promise<void>;
  playPause: () => Promise<void>;
  next: () => Promise<void>;
  previous: () => Promise<void>;
  seekToUnit: (index: number) => Promise<void>;
  stopPlayback: () => Promise<void>;
  getVoices: () => Promise<NativeTtsVoice[]>;
};

const nativeModule =
  requireOptionalNativeModule<NativeTtsMediaSessionModule>("TtsMediaSession");
const emitter = nativeModule ? new EventEmitter(nativeModule as any) : null;

export function isAvailable(): boolean {
  return !!nativeModule;
}

export function startPlayback(payload: TtsPlaybackPayload): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.startPlayback(
    payload.units,
    payload.title,
    payload.storyId,
    payload.chapterId,
    payload.startIndex ?? 0,
    payload.pitch,
    payload.rate,
    payload.voiceIdentifier,
  );
}

export function pausePlayback(): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.pausePlayback();
}

export function resumePlayback(): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.resumePlayback();
}

export function playPause(): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.playPause();
}

export function next(): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.next();
}

export function previous(): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.previous();
}

export function seekToUnit(index: number): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.seekToUnit(index);
}

export function stopPlayback(): Promise<void> {
  if (!nativeModule) return Promise.resolve();
  return nativeModule.stopPlayback();
}

export function getNativeVoices(): Promise<NativeTtsVoice[]> {
  if (!nativeModule) return Promise.resolve([]);
  return nativeModule.getVoices();
}

export function addMediaButtonListener(
  listener: (event: MediaButtonEvent) => void,
): EventSubscription | null {
  if (!emitter) return null;
  return (emitter as any).addListener("onMediaButton", listener);
}

export function addPlaybackStateListener(
  listener: (event: NativeTtsPlaybackState) => void,
): EventSubscription | null {
  if (!emitter) return null;
  return (emitter as any).addListener("onPlaybackState", listener);
}
