import { useCallback } from "react";
import { SortOption } from "./useLibrary";

interface UseSortControlsParams {
  sortOption: SortOption;
  sortDirection: "asc" | "desc";
  setSortOption: (option: SortOption) => void;
  setSortDirection: (direction: "asc" | "desc") => void;
}

/**
 * Provides stable callbacks for sort option selection and direction toggling.
 * Handles the toggle-on-same-option / set-default-direction-on-new-option logic.
 */
export function useSortControls({
  sortOption,
  sortDirection,
  setSortOption,
  setSortDirection,
}: UseSortControlsParams) {
  const handleSortSelect = useCallback(
    (option: SortOption) => {
      if (sortOption === option) {
        setSortDirection(sortDirection === "asc" ? "desc" : "asc");
      } else {
        setSortOption(option);
        setSortDirection(option === "title" ? "asc" : "desc");
      }
    },
    [sortOption, sortDirection, setSortOption, setSortDirection],
  );

  const handleToggleDirection = useCallback(() => {
    setSortDirection(sortDirection === "asc" ? "desc" : "asc");
  }, [sortDirection, setSortDirection]);

  return { handleSortSelect, handleToggleDirection };
}
