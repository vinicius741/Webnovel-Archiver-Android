import { useState, useCallback, useMemo } from "react";
import { useFocusEffect } from "expo-router";
import { storageService } from "../services/StorageService";
import { Tab } from "../types/tab";
import { Story } from "../types";

export const useTabData = () => {
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [stories, setStories] = useState<Story[]>([]);
  const [loading, setLoading] = useState(true);

  const loadData = useCallback(async () => {
    setLoading(true);
    const [loadedTabs, loadedStories] = await Promise.all([
      storageService.getTabs(),
      storageService.getLibrary(),
    ]);
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

  return {
    tabs,
    setTabs,
    stories,
    loading,
    storyCountByTab,
    unassignedCount,
    refresh: loadData,
  };
};
