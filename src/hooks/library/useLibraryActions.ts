import { useState, useCallback } from "react";
import { useAppAlert } from "../../context/AlertContext";

interface UseLibraryActionsParams {
  selectionMode: boolean;
  selectedCount: number;
  enterSelectionMode: () => void;
  toggleSelection: (id: string) => void;
  moveSelectedToTab: (tabId: string | null) => Promise<void>;
  deleteSelectedStories: () => Promise<void>;
  onRefresh: () => void;
}

/**
 * Manages selection interaction handlers: long-press to select,
 * move-to-tab dialog state, and delete confirmation.
 */
export function useLibraryActions({
  selectionMode,
  selectedCount,
  enterSelectionMode,
  toggleSelection,
  moveSelectedToTab,
  deleteSelectedStories,
  onRefresh,
}: UseLibraryActionsParams) {
  const [moveDialogVisible, setMoveDialogVisible] = useState(false);
  const { showAlert } = useAppAlert();

  const handleLongPress = useCallback(
    (storyId: string) => {
      if (!selectionMode) {
        enterSelectionMode();
      }
      toggleSelection(storyId);
    },
    [selectionMode, enterSelectionMode, toggleSelection],
  );

  const handleOpenMoveDialog = useCallback(() => {
    setMoveDialogVisible(true);
  }, []);

  const handleCloseMoveDialog = useCallback(() => {
    setMoveDialogVisible(false);
  }, []);

  const handleMove = useCallback(
    async (tabId: string | null) => {
      await moveSelectedToTab(tabId);
      setMoveDialogVisible(false);
      onRefresh();
    },
    [moveSelectedToTab, onRefresh],
  );

  const handleDelete = useCallback(() => {
    showAlert(
      "Delete Novels",
      `Are you sure you want to delete ${selectedCount} novel${selectedCount === 1 ? "" : "s"}? This cannot be undone.`,
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Delete",
          style: "destructive",
          onPress: async () => {
            await deleteSelectedStories();
            onRefresh();
          },
        },
      ],
    );
  }, [selectedCount, deleteSelectedStories, onRefresh, showAlert]);

  return {
    moveDialogVisible,
    handleLongPress,
    handleOpenMoveDialog,
    handleCloseMoveDialog,
    handleMove,
    handleDelete,
  };
}
