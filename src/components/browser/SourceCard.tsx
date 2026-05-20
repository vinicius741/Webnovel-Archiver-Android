import React from "react";
import { StyleSheet, View } from "react-native";
import { Card, Text, IconButton, useTheme } from "react-native-paper";
import { useAppTheme } from "../../theme/useAppTheme";

interface SourceCardProps {
  title: string;
  subtitle: string;
  icon: string;
  accentColor: string;
  onPress: () => void;
}

export const SourceCard: React.FC<SourceCardProps> = ({
  title,
  subtitle,
  icon,
  accentColor,
  onPress,
}) => {
  const theme = useTheme();
  const appTheme = useAppTheme();

  return (
    <Card
      style={[styles.card, { borderRadius: appTheme.shapes.cardRadius }]}
      onPress={onPress}
    >
      <Card.Content style={styles.content}>
        <View style={[styles.iconCircle, { backgroundColor: accentColor }]}>
          <IconButton icon={icon} iconColor="#FFF" size={24} style={styles.icon} />
        </View>
        <Text variant="titleSmall" style={{ color: theme.colors.onSurface, textAlign: "center" }}>
          {title}
        </Text>
        <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant, textAlign: "center" }}>
          {subtitle}
        </Text>
      </Card.Content>
    </Card>
  );
};

const styles = StyleSheet.create({
  card: {
    flex: 1,
  },
  content: {
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 20,
    paddingHorizontal: 12,
  },
  iconCircle: {
    width: 48,
    height: 48,
    borderRadius: 24,
    justifyContent: "center",
    alignItems: "center",
    marginBottom: 8,
  },
  icon: {
    margin: 0,
    padding: 0,
    width: 48,
    height: 48,
  },
});
