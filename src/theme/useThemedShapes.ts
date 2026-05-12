import { useAppTheme } from "../theme/useAppTheme";

export function useThemedShapes() {
  const theme = useAppTheme();
  return {
    card: {
      borderRadius: theme.shapes.cardRadius,
      ...(theme.shapes.elevationStyle === "border" && {
        borderWidth: 1,
        borderColor: theme.colors.outline,
      }),
    },
    dialog: { borderRadius: theme.shapes.dialogRadius },
    fab: { borderRadius: theme.shapes.fabRadius },
    chip: { borderRadius: theme.shapes.chipRadius },
    searchBar: { borderRadius: theme.shapes.searchBarRadius },
  };
}
