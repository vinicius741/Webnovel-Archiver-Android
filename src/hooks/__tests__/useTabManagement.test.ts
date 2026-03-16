import { renderHook, act, waitFor } from "@testing-library/react-native";
import { useTabManagement } from "../useTabManagement";
import { storageService } from "../../services/StorageService";
import { DownloadStatus, Story } from "../../types";
import { Tab } from "../../types/tab";

jest.mock("../../services/StorageService", () => ({
  storageService: {
    getTabs: jest.fn().mockResolvedValue([]),
    getLibrary: jest.fn().mockResolvedValue([]),
    saveTabs: jest.fn().mockResolvedValue(undefined),
    moveStoriesToTab: jest.fn().mockResolvedValue(undefined),
  },
}));

jest.mock("expo-router", () => ({
  useFocusEffect: jest.fn((callback) => {
    const { useEffect } = require("react");
    useEffect(callback, []);
  }),
}));

describe("useTabManagement", () => {
  const mockTabs: Tab[] = [
    { id: "tab-1", name: "Reading", order: 0, createdAt: 1000 },
    { id: "tab-2", name: "Completed", order: 1, createdAt: 2000 },
  ];

  const mockStory: Story = {
    id: "story-1",
    title: "Test Story",
    author: "Author",
    sourceUrl: "http://test.com/story",
    coverUrl: "http://test.com/cover.jpg",
    chapters: [],
    status: DownloadStatus.Idle,
    totalChapters: 0,
    downloadedChapters: 0,
    dateAdded: 1000,
    lastUpdated: 2000,
    tags: [],
    tabId: "tab-1",
  };

  beforeEach(() => {
    jest.clearAllMocks();
    (storageService.getTabs as jest.Mock).mockResolvedValue(mockTabs);
    (storageService.getLibrary as jest.Mock).mockResolvedValue([mockStory]);
  });

  describe("initialization", () => {
    it("should load tabs and stories on mount", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(storageService.getTabs).toHaveBeenCalled();
      expect(storageService.getLibrary).toHaveBeenCalled();
      expect(result.current.tabs).toHaveLength(2);
    });

    it("should sort tabs by order", async () => {
      const unsortedTabs: Tab[] = [
        { id: "tab-2", name: "Second", order: 1, createdAt: 2000 },
        { id: "tab-1", name: "First", order: 0, createdAt: 1000 },
      ];
      (storageService.getTabs as jest.Mock).mockResolvedValue(unsortedTabs);

      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.tabs[0].order).toBe(0);
      expect(result.current.tabs[1].order).toBe(1);
    });
  });

  describe("storyCountByTab", () => {
    it("should count stories per tab", async () => {
      const stories: Story[] = [
        { ...mockStory, id: "s1", tabId: "tab-1" },
        { ...mockStory, id: "s2", tabId: "tab-1" },
        { ...mockStory, id: "s3", tabId: "tab-2" },
      ];
      (storageService.getLibrary as jest.Mock).mockResolvedValue(stories);

      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.storyCountByTab["tab-1"]).toBe(2);
      expect(result.current.storyCountByTab["tab-2"]).toBe(1);
    });

    it("should initialize all tabs with count 0", async () => {
      (storageService.getLibrary as jest.Mock).mockResolvedValue([]);

      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.storyCountByTab["tab-1"]).toBe(0);
      expect(result.current.storyCountByTab["tab-2"]).toBe(0);
    });
  });

  describe("unassignedCount", () => {
    it("should count stories without tab", async () => {
      const stories: Story[] = [
        { ...mockStory, id: "s1", tabId: undefined },
        { ...mockStory, id: "s2", tabId: undefined },
        { ...mockStory, id: "s3", tabId: "tab-1" },
      ];
      (storageService.getLibrary as jest.Mock).mockResolvedValue(stories);

      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.unassignedCount).toBe(2);
    });
  });

  describe("addTab", () => {
    it("should add a new tab", async () => {
      (storageService.getTabs as jest.Mock).mockResolvedValue([]);

      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let success: boolean | undefined;
      await act(async () => {
        success = await result.current.addTab("New Tab");
      });

      expect(success).toBe(true);
      expect(storageService.saveTabs).toHaveBeenCalled();
      expect(result.current.tabs).toHaveLength(1);
      expect(result.current.tabs[0].name).toBe("New Tab");
    });

    it("should not add tab with empty name", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let success: boolean | undefined;
      await act(async () => {
        success = await result.current.addTab("   ");
      });

      expect(success).toBe(false);
      expect(storageService.saveTabs).not.toHaveBeenCalled();
    });

    it("should assign correct order to new tab", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      await act(async () => {
        await result.current.addTab("Third Tab");
      });

      const savedTabs = (storageService.saveTabs as jest.Mock).mock.calls[0][0];
      expect(savedTabs[2].order).toBe(2);
    });
  });

  describe("updateTab", () => {
    it("should update tab name", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let success: boolean | undefined;
      await act(async () => {
        success = await result.current.updateTab("tab-1", "Updated Name");
      });

      expect(success).toBe(true);
      expect(result.current.tabs[0].name).toBe("Updated Name");
    });

    it("should not update tab with empty name", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let success: boolean | undefined;
      await act(async () => {
        success = await result.current.updateTab("tab-1", "");
      });

      expect(success).toBe(false);
      expect(storageService.saveTabs).not.toHaveBeenCalled();
    });

    it("should trim tab name", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      await act(async () => {
        await result.current.updateTab("tab-1", "  Trimmed  ");
      });

      expect(result.current.tabs[0].name).toBe("Trimmed");
    });
  });

  describe("deleteTab", () => {
    it("should delete tab and re-order remaining", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let count: number | undefined;
      await act(async () => {
        count = await result.current.deleteTab("tab-1");
      });

      expect(count).toBe(1); // One story was in this tab
      expect(result.current.tabs).toHaveLength(1);
      expect(result.current.tabs[0].order).toBe(0); // Re-ordered
    });

    it("should move stories to unassigned", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      await act(async () => {
        await result.current.deleteTab("tab-1");
      });

      expect(storageService.moveStoriesToTab).toHaveBeenCalledWith(
        ["story-1"],
        null,
      );
    });

    it("should return 0 for empty tab", async () => {
      (storageService.getLibrary as jest.Mock).mockResolvedValue([]);

      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      let count: number | undefined;
      await act(async () => {
        count = await result.current.deleteTab("tab-1");
      });

      expect(count).toBe(0);
    });
  });

  describe("moveTabUp", () => {
    it("should move tab up", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      await act(async () => {
        await result.current.moveTabUp(1);
      });

      expect(result.current.tabs[0].id).toBe("tab-2");
      expect(result.current.tabs[1].id).toBe("tab-1");
    });

    it("should not move tab up if at top", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      await act(async () => {
        await result.current.moveTabUp(0);
      });

      // Should still be in original order
      expect(result.current.tabs[0].id).toBe("tab-1");
    });
  });

  describe("moveTabDown", () => {
    it("should move tab down", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      await act(async () => {
        await result.current.moveTabDown(0);
      });

      expect(result.current.tabs[0].id).toBe("tab-2");
      expect(result.current.tabs[1].id).toBe("tab-1");
    });

    it("should not move tab down if at bottom", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      await act(async () => {
        await result.current.moveTabDown(1);
      });

      // Should still be in original order
      expect(result.current.tabs[1].id).toBe("tab-2");
    });
  });

  describe("refresh", () => {
    it("should reload data", async () => {
      const { result } = renderHook(() => useTabManagement());

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      // Clear calls from initial load
      (storageService.getTabs as jest.Mock).mockClear();

      await act(async () => {
        await result.current.refresh();
      });

      expect(storageService.getTabs).toHaveBeenCalled();
      expect(storageService.getLibrary).toHaveBeenCalled();
    });
  });
});
