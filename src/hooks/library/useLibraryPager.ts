import { useCallback, useEffect, useMemo, useRef } from "react";
import PagerView from "react-native-pager-view";
import { Tab } from "../../types/tab";

interface UseLibraryPagerParams {
  tabs: Tab[];
  activeTabId: string | null;
  hasCustomTabs: boolean;
  showUnassignedTab: boolean;
  selectTab: (tabId: string | null) => void;
}

/**
 * Manages the PagerView ref and coordinates page state with the active tab.
 * Returns the pager ref, computed page tab IDs, active page index,
 * and handlers for page-selected and tab-bar selection events.
 */
export function useLibraryPager({
  tabs,
  activeTabId,
  hasCustomTabs,
  showUnassignedTab,
  selectTab,
}: UseLibraryPagerParams) {
  const pagerRef = useRef<PagerView>(null);

  const pageTabIds = useMemo(() => {
    if (!hasCustomTabs) return [];
    const ids = tabs.map((t) => t.id);
    if (showUnassignedTab) {
      ids.push("unassigned");
    }
    return ids;
  }, [hasCustomTabs, tabs, showUnassignedTab]);

  const activePageIndex = useMemo(() => {
    const idx = pageTabIds.indexOf(activeTabId ?? "");
    return idx >= 0 ? idx : 0;
  }, [pageTabIds, activeTabId]);

  // Keep pager in sync with active tab
  useEffect(() => {
    if (hasCustomTabs && pagerRef.current && activeTabId) {
      pagerRef.current.setPage(activePageIndex);
    }
  }, [activeTabId, activePageIndex, hasCustomTabs]);

  const handlePageSelected = useCallback(
    (e: { nativeEvent: { position: number } }) => {
      const tabId = pageTabIds[e.nativeEvent.position];
      if (tabId && tabId !== activeTabId) {
        selectTab(tabId);
      }
    },
    [pageTabIds, activeTabId, selectTab],
  );

  const handleSelectTabFromBar = useCallback(
    (tabId: string | null) => {
      selectTab(tabId);
      if (tabId && pagerRef.current) {
        const idx = pageTabIds.indexOf(tabId);
        if (idx >= 0) {
          pagerRef.current.setPage(idx);
        }
      }
    },
    [selectTab, pageTabIds],
  );

  return {
    pagerRef,
    pageTabIds,
    activePageIndex,
    handlePageSelected,
    handleSelectTabFromBar,
  };
}
