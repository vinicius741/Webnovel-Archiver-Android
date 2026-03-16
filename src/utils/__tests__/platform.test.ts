import { Platform } from "react-native";
import Constants from "expo-constants";
import { isExpoGo, isAndroidNative } from "../platform";

jest.mock("react-native", () => ({
  Platform: {
    OS: "android",
  },
}));

jest.mock("expo-constants", () => ({
  executionEnvironment: "storeClient",
}));

describe("platform utilities", () => {
  describe("isExpoGo", () => {
    it("should return true when running in Expo Go", () => {
      Object.defineProperty(Constants, "executionEnvironment", {
        value: "storeClient",
        writable: true,
      });

      expect(isExpoGo()).toBe(true);
    });

    it("should return false when running in standalone app", () => {
      Object.defineProperty(Constants, "executionEnvironment", {
        value: "standalone",
        writable: true,
      });

      expect(isExpoGo()).toBe(false);
    });

    it("should return false when running in bare workflow", () => {
      Object.defineProperty(Constants, "executionEnvironment", {
        value: "bare",
        writable: true,
      });

      expect(isExpoGo()).toBe(false);
    });
  });

  describe("isAndroidNative", () => {
    beforeEach(() => {
      // Reset Platform.OS mock
      Object.defineProperty(Platform, "OS", {
        value: "android",
        writable: true,
      });
    });

    it("should return true on Android standalone", () => {
      Object.defineProperty(Constants, "executionEnvironment", {
        value: "standalone",
        writable: true,
      });

      expect(isAndroidNative()).toBe(true);
    });

    it("should return false on Android with Expo Go", () => {
      Object.defineProperty(Constants, "executionEnvironment", {
        value: "storeClient",
        writable: true,
      });

      expect(isAndroidNative()).toBe(false);
    });

    it("should return false on iOS", () => {
      Object.defineProperty(Platform, "OS", {
        value: "ios",
        writable: true,
      });
      Object.defineProperty(Constants, "executionEnvironment", {
        value: "standalone",
        writable: true,
      });

      expect(isAndroidNative()).toBe(false);
    });

    it("should return false on iOS with Expo Go", () => {
      Object.defineProperty(Platform, "OS", {
        value: "ios",
        writable: true,
      });
      Object.defineProperty(Constants, "executionEnvironment", {
        value: "storeClient",
        writable: true,
      });

      expect(isAndroidNative()).toBe(false);
    });
  });
});
