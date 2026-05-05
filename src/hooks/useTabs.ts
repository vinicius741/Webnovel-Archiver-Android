import { useState, useCallback } from "react";
import { useTabData } from "./useTabData";

export const useTabs = () => {
  const { tabs, loading, unassignedCount, refresh } = useTabData();
  const [activeTabId, setActiveTabId] = useState<string | null>(null);

  const hasCustomTabs = tabs.length > 0;
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
    refresh,
  };
};
