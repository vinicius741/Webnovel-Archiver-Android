import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Text, FAB, useTheme } from 'react-native-paper';
import { useRouter } from 'expo-router';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { StoryCard } from '../src/components/StoryCard';

export default function HomeScreen() {
  const router = useRouter();
  const theme = useTheme();

  return (
    <ScreenContainer>
      <View style={styles.content}>
        {/* Placeholder List */}
        <Text variant="headlineMedium" style={styles.title}>Library</Text>
        {true ? ( // Toggle false to see empty state
             <StoryCard 
                title="The Beginning After The End" 
                author="TurtleMe" 
                progress={0.7} 
                onPress={() => {}} 
             />
        ) : (
            <Text variant="bodyLarge" style={styles.placeholder}>
            No stories archived yet. Tap + to add one.
            </Text>
        )}
      </View>
      
      <FAB
        icon="plus"
        style={[styles.fab, { backgroundColor: theme.colors.primary }]}
        onPress={() => router.push('/add')}
        color={theme.colors.onPrimary}
      />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  content: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  title: {
    marginBottom: 8,
  },
  placeholder: {
    opacity: 0.6,
  },
  fab: {
    position: 'absolute',
    margin: 16,
    right: 0,
    bottom: 0,
  },
});
