import { useMemo } from "react";

import { useScreenLayout } from "../common/useScreenLayout";

type WidthClass = "compact" | "medium" | "expanded";

export interface DownloadManagerLayout {
  shellPadding: number;
  queueMaxWidth: number;
  statsColumns: number;
  statItemStyle: { width: number };
}

const MIN_STAT_WIDTH = 88;
const GAP = 8;

export function useDownloadManagerLayout(): DownloadManagerLayout {
  const { screenWidth, widthClass } = useScreenLayout();

  const resolvedWidthClass: WidthClass =
    widthClass ||
    (screenWidth >= 960
      ? "expanded"
      : screenWidth >= 600
        ? "medium"
        : "compact");

  const shellPadding = useMemo(() => {
    if (resolvedWidthClass === "expanded") return 32;
    if (resolvedWidthClass === "medium") return 24;
    return 16;
  }, [resolvedWidthClass]);

  const queueMaxWidth = resolvedWidthClass === "expanded" ? 1080 : 920;

  const cardWidth =
    screenWidth > 0
      ? Math.min(queueMaxWidth, Math.max(screenWidth - shellPadding * 2, 0))
      : queueMaxWidth;
  const contentWidth = Math.max(0, cardWidth - 32);

  const maxCols = Math.floor((contentWidth + GAP) / (MIN_STAT_WIDTH + GAP));
  const statsColumns = maxCols >= 5 ? 5 : maxCols >= 3 ? 3 : 1;

  const statItemWidth = Math.max(
    MIN_STAT_WIDTH,
    Math.floor((contentWidth - (statsColumns - 1) * GAP) / statsColumns),
  );

  const statItemStyle = useMemo(
    () => ({ width: statItemWidth }),
    [statItemWidth],
  );

  return {
    shellPadding,
    queueMaxWidth,
    statsColumns,
    statItemStyle,
  };
}
