import { useCallback } from "react";
import { storageService } from "../services/StorageService";
import { Tab } from "../types/tab";
import { useTabData } from "./useTabData";

export const useTabManagement = () => {
  const {
    tabs,
    setTabs,
    stories,
    loading,
    storyCountByTab,
    unassignedCount,
    refresh,
  } = useTabData();

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
    [tabs, setTabs],
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
    [tabs, setTabs],
  );

  const deleteTab = useCallback(
    async (id: string): Promise<number> => {
      const count = storyCountByTab[id] || 0;

      const filteredTabs = tabs.filter((tab) => tab.id !== id);
      const reorderedTabs = filteredTabs.map((tab, index) => ({
        ...tab,
        order: index,
      }));

      await storageService.moveStoriesToTab(
        stories.filter((s) => s.tabId === id).map((s) => s.id),
        null,
      );

      await storageService.saveTabs(reorderedTabs);
      setTabs(reorderedTabs);

      return count;
    },
    [tabs, storyCountByTab, stories, setTabs],
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
    [tabs, setTabs],
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
    refresh,
  };
};
