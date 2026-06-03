import { useState, useCallback, useRef } from "react";
import { ChapterFilterMode } from "../../services/storage/StorageService";

interface UseChapterFilterMenuProps {
  onFilterSelect: (mode: ChapterFilterMode) => void;
}

export const useChapterFilterMenu = ({ onFilterSelect }: UseChapterFilterMenuProps) => {
  const [visible, setVisible] = useState(false);
  const [menuKey, setMenuKey] = useState(0);
  const isDismissedRef = useRef(false);

  const openMenu = useCallback(() => {
    // Prevent reopening immediately after dismiss (touch event conflict)
    if (isDismissedRef.current) {
      isDismissedRef.current = false;
      return;
    }
    setVisible(true);
  }, []);

  const closeMenu = useCallback(() => {
    setVisible(false);
    // Force remount of Menu component to reset internal state
    setMenuKey((k) => k + 1);
  }, []);

  const handleDismiss = useCallback(() => {
    isDismissedRef.current = true;
    closeMenu();
    // Reset the flag after a short delay
    setTimeout(() => {
      isDismissedRef.current = false;
    }, 150);
  }, [closeMenu]);

  const handleSelect = useCallback(
    (mode: ChapterFilterMode) => {
      closeMenu();
      // Small delay to let menu close animation complete before callback
      setTimeout(() => {
        onFilterSelect(mode);
      }, 100);
    },
    [onFilterSelect, closeMenu],
  );

  return {
    visible,
    menuKey,
    openMenu,
    handleDismiss,
    handleSelect,
  };
};
