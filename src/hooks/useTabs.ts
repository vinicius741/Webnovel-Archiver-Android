import { useState, useCallback, useMemo } from "react";
import { useFocusEffect } from "expo-router";
import { storageService } from "../services/StorageService";
import { Tab } from "../types/tab";
import { Story } from "../types";

export const useTabs = () => {
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [stories, setStories] = useState<Story[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
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

  const hasCustomTabs = tabs.length > 0;

  const unassignedCount = useMemo(() => {
    return stories.filter((s) => !s.tabId).length;
  }, [stories]);

  const showUnassignedTab = hasCustomTabs && unassignedCount > 0;

  const selectTab = useCallback((tabId: string | null) => {
    setActiveTabId(tabId);
  }, []);

  return {
    tabs,
    activeTabId,
    hasCustomTabs,
    showUnassignedTab,
    unassignedCount,
    selectTab,
    loading,
    refresh: loadData,
  };
};
