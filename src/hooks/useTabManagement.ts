import { useState, useCallback, useMemo } from "react";
import { useFocusEffect } from "expo-router";
import { storageService } from "../services/StorageService";
import { Tab } from "../types/tab";
import { Story } from "../types";

export const useTabManagement = () => {
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [stories, setStories] = useState<Story[]>([]);
  const [loading, setLoading] = useState(true);

  const loadData = useCallback(async () => {
    setLoading(true);
    const [loadedTabs, loadedStories] = await Promise.all([
      storageService.getTabs(),
      storageService.getLibrary(),
    ]);
    // Sort tabs by order
    setTabs(loadedTabs.sort((a, b) => a.order - b.order));
    setStories(loadedStories);
    setLoading(false);
  }, []);

  useFocusEffect(
    useCallback(() => {
      void loadData();
    }, [loadData]),
  );

  const storyCountByTab = useMemo(() => {
    const counts: Record<string, number> = {};
    tabs.forEach((tab) => {
      counts[tab.id] = 0;
    });
    stories.forEach((story) => {
      if (story.tabId && counts[story.tabId] !== undefined) {
        counts[story.tabId]++;
      }
    });
    return counts;
  }, [tabs, stories]);

  const unassignedCount = useMemo(() => {
    return stories.filter((s) => !s.tabId).length;
  }, [stories]);

  const addTab = useCallback(
    async (name: string): Promise<boolean> => {
      if (!name.trim()) return false;

      const newTab: Tab = {
        id: `tab_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`,
        name: name.trim(),
        order: tabs.length,
        createdAt: Date.now(),
      };

      const updatedTabs = [...tabs, newTab];
      await storageService.saveTabs(updatedTabs);
      setTabs(updatedTabs);
      return true;
    },
    [tabs],
  );

  const updateTab = useCallback(
    async (id: string, name: string): Promise<boolean> => {
      if (!name.trim()) return false;

      const updatedTabs = tabs.map((tab) =>
        tab.id === id ? { ...tab, name: name.trim() } : tab,
      );
      await storageService.saveTabs(updatedTabs);
      setTabs(updatedTabs);
      return true;
    },
    [tabs],
  );

  const deleteTab = useCallback(
    async (id: string): Promise<number> => {
      // Get count of stories in this tab
      const count = storyCountByTab[id] || 0;

      // Remove tab and re-order remaining tabs
      const filteredTabs = tabs.filter((tab) => tab.id !== id);
      const reorderedTabs = filteredTabs.map((tab, index) => ({
        ...tab,
        order: index,
      }));

      // Move stories in this tab to unassigned
      await storageService.moveStoriesToTab(
        stories.filter((s) => s.tabId === id).map((s) => s.id),
        null,
      );

      await storageService.saveTabs(reorderedTabs);
      setTabs(reorderedTabs);

      return count;
    },
    [tabs, storyCountByTab, stories],
  );

  const reorderTabs = useCallback(
    async (fromIndex: number, toIndex: number): Promise<void> => {
      const newTabs = [...tabs];
      const [movedTab] = newTabs.splice(fromIndex, 1);
      newTabs.splice(toIndex, 0, movedTab);

      const reorderedTabs = newTabs.map((tab, index) => ({
        ...tab,
        order: index,
      }));

      await storageService.saveTabs(reorderedTabs);
      setTabs(reorderedTabs);
    },
    [tabs],
  );

  const moveTabUp = useCallback(
    async (index: number): Promise<void> => {
      if (index <= 0) return;
      await reorderTabs(index, index - 1);
    },
    [reorderTabs],
  );

  const moveTabDown = useCallback(
    async (index: number): Promise<void> => {
      if (index >= tabs.length - 1) return;
      await reorderTabs(index, index + 1);
    },
    [reorderTabs, tabs.length],
  );

  return {
    tabs,
    loading,
    storyCountByTab,
    unassignedCount,
    addTab,
    updateTab,
    deleteTab,
    moveTabUp,
    moveTabDown,
    refresh: loadData,
  };
};
