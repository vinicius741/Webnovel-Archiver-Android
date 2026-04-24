import { sourceRegistry } from "../SourceRegistry";
import { SourceProvider } from "../types";

// Mock RoyalRoadProvider to avoid importing the real implementation
jest.mock("../providers/RoyalRoadProvider", () => ({
  RoyalRoadProvider: {
    name: "RoyalRoad",
    isSource: (url: string) => url.includes("royalroad.com"),
  },
}));

jest.mock("../providers/ScribbleHubProvider", () => ({
  ScribbleHubProvider: {
    name: "Scribble Hub",
    isSource: (url: string) => url.includes("scribblehub.com/series/"),
  },
}));

describe("SourceRegistry", () => {
  describe("default registration", () => {
    it("should register RoyalRoadProvider by default", () => {
      const provider = sourceRegistry.getProvider(
        "https://www.royalroad.com/fiction/12345/test-story",
      );

      expect(provider).toBeDefined();
      expect(provider?.name).toBe("RoyalRoad");
    });

    it("should register ScribbleHubProvider by default", () => {
      const provider = sourceRegistry.getProvider(
        "https://www.scribblehub.com/series/1056226/outrun--cyberpunk-litrpg/",
      );

      expect(provider).toBeDefined();
      expect(provider?.name).toBe("Scribble Hub");
    });
  });

  describe("register", () => {
    it("should register a new provider", () => {
      const customProvider: SourceProvider = {
        name: "CustomProvider",
        baseUrl: "https://custom.com",
        isSource: (url) => url.includes("custom.com"),
        getStoryId: (url) => url.split("/").pop() || "",
        parseMetadata: (html: string) => ({ title: "", author: "" }),
        getChapterList: async () => [],
        parseChapterContent: (html: string) => "",
      };

      sourceRegistry.register(customProvider);

      const provider = sourceRegistry.getProvider("https://custom.com/story/123");
      expect(provider).toBe(customProvider);
    });

    it("should allow multiple providers", () => {
      const provider1: SourceProvider = {
        name: "Provider1",
        baseUrl: "https://provider1.com",
        isSource: (url) => url.includes("provider1.com"),
        getStoryId: (url) => url.split("/").pop() || "",
        parseMetadata: (html: string) => ({ title: "", author: "" }),
        getChapterList: async () => [],
        parseChapterContent: (html: string) => "",
      };
      const provider2: SourceProvider = {
        name: "Provider2",
        baseUrl: "https://provider2.com",
        isSource: (url) => url.includes("provider2.com"),
        getStoryId: (url) => url.split("/").pop() || "",
        parseMetadata: (html: string) => ({ title: "", author: "" }),
        getChapterList: async () => [],
        parseChapterContent: (html: string) => "",
      };

      sourceRegistry.register(provider1);
      sourceRegistry.register(provider2);

      expect(sourceRegistry.getProvider("https://provider1.com/story")).toBe(
        provider1,
      );
      expect(sourceRegistry.getProvider("https://provider2.com/story")).toBe(
        provider2,
      );
    });
  });

  describe("getProvider", () => {
    it("should return provider for matching URL", () => {
      const provider: SourceProvider = {
        name: "TestProvider",
        baseUrl: "https://test.com",
        isSource: (url) => url.includes("test.com"),
        getStoryId: (url) => url.split("/").pop() || "",
        parseMetadata: (html: string) => ({ title: "", author: "" }),
        getChapterList: async () => [],
        parseChapterContent: (html: string) => "",
      };
      sourceRegistry.register(provider);

      const result = sourceRegistry.getProvider("https://test.com/fiction/123");

      expect(result).toBe(provider);
    });

    it("should return undefined for non-matching URL", () => {
      const result = sourceRegistry.getProvider("https://unknown.com/story");

      expect(result).toBeUndefined();
    });

    it("should return first matching provider", () => {
      const provider1: SourceProvider = {
        name: "ProviderFirst",
        baseUrl: "https://first.com",
        isSource: () => true,
        getStoryId: (url) => url.split("/").pop() || "",
        parseMetadata: (html: string) => ({ title: "", author: "" }),
        getChapterList: async () => [],
        parseChapterContent: (html: string) => "",
      };
      sourceRegistry.register(provider1);

      const result = sourceRegistry.getProvider("https://any.url/story");

      expect(result).toBe(provider1);
    });

    it("should handle various URL formats", () => {
      // Test RoyalRoad URLs
      expect(
        sourceRegistry.getProvider("https://www.royalroad.com/fiction/12345/title"),
      ).toBeDefined();
      expect(
        sourceRegistry.getProvider("http://royalroad.com/fiction/12345/title"),
      ).toBeDefined();
      expect(
        sourceRegistry.getProvider("https://royalroad.com/fiction/12345/title"),
      ).toBeDefined();
      expect(
        sourceRegistry.getProvider("https://www.scribblehub.com/series/123/title"),
      ).toBeDefined();
    });
  });
});
