import React from "react";
import { StyleSheet, View, ScrollView } from "react-native";
import { Text, IconButton, useTheme } from "react-native-paper";
import { SourceCard } from "./SourceCard";

interface BrowserLandingProps {
  onNavigate: (url: string) => void;
}

export const BrowserLanding: React.FC<BrowserLandingProps> = ({ onNavigate }) => {
  const theme = useTheme();

  return (
    <ScrollView contentContainerStyle={styles.landingContainer}>
      <View style={styles.welcomeSection}>
        <IconButton icon="compass-rose" size={64} iconColor={theme.colors.primary} style={styles.welcomeIcon} />
        <Text variant="headlineMedium" style={styles.welcomeTitle}>
          Source Browser
        </Text>
        <Text variant="bodyMedium" style={[styles.welcomeSub, { color: theme.colors.onSurfaceVariant }]}>
          Browse novel websites directly. Navigate to any webnovel's detail page, and tap the download button to import it.
        </Text>
      </View>

      <Text variant="titleMedium" style={styles.sectionTitle}>
        Supported Novel Sites
      </Text>

      <View style={styles.sourcesGrid}>
        <SourceCard
          title="Royal Road"
          subtitle="royalroad.com"
          icon="compass-outline"
          accentColor="#D85A38"
          onPress={() => onNavigate("https://www.royalroad.com")}
        />

        <SourceCard
          title="Scribble Hub"
          subtitle="scribblehub.com"
          icon="book-open-variant"
          accentColor="#7B2CBF"
          onPress={() => onNavigate("https://www.scribblehub.com")}
        />
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  landingContainer: {
    padding: 24,
    paddingBottom: 48,
    alignItems: "center",
  },
  welcomeSection: {
    alignItems: "center",
    marginBottom: 32,
    marginTop: 20,
  },
  welcomeIcon: {
    margin: 0,
  },
  welcomeTitle: {
    fontWeight: "bold",
    marginVertical: 8,
  },
  welcomeSub: {
    textAlign: "center",
    lineHeight: 20,
    paddingHorizontal: 16,
  },
  sectionTitle: {
    alignSelf: "flex-start",
    marginBottom: 16,
    fontWeight: "bold",
  },
  sourcesGrid: {
    flexDirection: "row",
    gap: 16,
    width: "100%",
  },
});
