import { DeviceEventEmitter } from "react-native";
import type { TTSState } from "../TTSStateManager";
import { TTS_STATE_EVENTS } from "../TTSStateManager";

const STATE_EMIT_DEBOUNCE_MS = 100;

export class TTSStateEmitter {
  private stateEmitTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private pendingStateEmit: TTSState | null = null;

  emitStateChange(state: TTSState): void {
    this.pendingStateEmit = state;

    if (this.stateEmitTimeoutId) {
      return;
    }

    this.stateEmitTimeoutId = setTimeout(() => {
      this.stateEmitTimeoutId = null;
      if (this.pendingStateEmit) {
        DeviceEventEmitter.emit(
          TTS_STATE_EVENTS.STATE_CHANGED,
          this.pendingStateEmit,
        );
        this.pendingStateEmit = null;
      }
    }, STATE_EMIT_DEBOUNCE_MS);
  }

  cleanup(): void {
    try {
      if (this.stateEmitTimeoutId) {
        clearTimeout(this.stateEmitTimeoutId);
        this.stateEmitTimeoutId = null;
      }
    } catch {
      // Ignore cleanup errors
    }
  }
}
