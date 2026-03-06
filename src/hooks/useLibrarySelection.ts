import { useState, useCallback } from "react";
import { storageService } from "../services/StorageService";

export const useLibrarySelection = () => {
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  const enterSelectionMode = useCallback(() => {
    setSelectionMode(true);
    setSelectedIds(new Set());
  }, []);

  const exitSelectionMode = useCallback(() => {
    setSelectionMode(false);
    setSelectedIds(new Set());
  }, []);

  const toggleSelection = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(id)) {
        newSet.delete(id);
      } else {
        newSet.add(id);
      }
      return newSet;
    });
  }, []);

  const selectAll = useCallback((ids: string[]) => {
    setSelectedIds(new Set(ids));
  }, []);

  const isSelected = useCallback(
    (id: string) => selectedIds.has(id),
    [selectedIds],
  );

  const selectedCount = selectedIds.size;

  const moveSelectedToTab = useCallback(
    async (tabId: string | null): Promise<void> => {
      const ids = Array.from(selectedIds);
      if (ids.length === 0) return;
      try {
        await storageService.moveStoriesToTab(ids, tabId);
        exitSelectionMode();
      } catch (error) {
        console.error("Failed to move stories to tab", error);
        // Don't exit selection mode on error - let user retry
        throw error;
      }
    },
    [selectedIds, exitSelectionMode],
  );

  return {
    selectionMode,
    selectedCount,
    enterSelectionMode,
    exitSelectionMode,
    toggleSelection,
    selectAll,
    isSelected,
    moveSelectedToTab,
  };
};
