import React from "react";
import { StyleSheet } from "react-native";
import { Card, useTheme } from "react-native-paper";
import type { MD3Theme } from "react-native-paper";
import type { QueueStats } from "../../services/download/types";
import { StatItem } from "./StatItem";

interface DownloadStatsBarProps {
  stats: QueueStats;
  statItemStyle: { width: number };
  shellPadding: number;
  queueMaxWidth: number;
}

export function DownloadStatsBar({
  stats,
  statItemStyle,
  shellPadding,
  queueMaxWidth,
}: DownloadStatsBarProps) {
  const theme = useTheme<MD3Theme>();
  const hasFailedJobs = stats.failed > 0;

  return (
    <Card
      style={[
        styles.statsCard,
        {
          marginHorizontal: shellPadding,
          maxWidth: queueMaxWidth,
          alignSelf: "center",
        },
      ]}
    >
      <Card.Content style={styles.statsContent}>
        <StatItem
          icon="download"
          value={stats.active}
          label="Active"
          color={theme.colors.primary}
          theme={theme}
          style={statItemStyle}
        />
        <StatItem
          icon="clock-outline"
          value={stats.pending}
          label="Queued"
          theme={theme}
          style={statItemStyle}
        />
        <StatItem
          icon="pause"
          value={stats.paused}
          label="Paused"
          theme={theme}
          style={statItemStyle}
        />
        <StatItem
          icon="check-circle"
          value={stats.completed}
          label="Done"
          color={theme.colors.secondary}
          theme={theme}
          style={statItemStyle}
        />
        <StatItem
          icon="alert-circle"
          value={stats.failed}
          label="Failed"
          color={hasFailedJobs ? theme.colors.error : undefined}
          theme={theme}
          style={statItemStyle}
        />
      </Card.Content>
    </Card>
  );
}

const styles = StyleSheet.create({
  statsCard: {
    marginTop: 8,
    marginBottom: 8,
    width: "100%",
  },
  statsContent: {
    flexDirection: "row",
    flexWrap: "wrap",
    justifyContent: "center",
    gap: 8,
    paddingVertical: 12,
  },
});
