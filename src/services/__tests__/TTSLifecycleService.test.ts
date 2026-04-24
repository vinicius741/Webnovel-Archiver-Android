import { ttsLifecycleService } from "../TTSLifecycleService";

describe("TTSLifecycleService", () => {
  it("starts and stops without JS speech watchdog work", () => {
    expect(() => ttsLifecycleService.start()).not.toThrow();
    expect(() => ttsLifecycleService.stop()).not.toThrow();
  });
});
