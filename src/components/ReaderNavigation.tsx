import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Appbar, Text, useTheme } from 'react-native-paper';

interface ReaderNavigationProps {
    currentChapterIndex: number;
    totalChapters: number;
    hasPrevious: boolean;
    hasNext: boolean;
    onPrevious: () => void;
    onNext: () => void;
}

export const ReaderNavigation: React.FC<ReaderNavigationProps> = ({
    currentChapterIndex,
    totalChapters,
    hasPrevious,
    hasNext,
    onPrevious,
    onNext
}) => {
    const theme = useTheme();

    return (
        <Appbar style={[styles.bottomBar, { backgroundColor: theme.colors.elevation.level2 }]}>
            <Appbar.Action 
                icon="chevron-left" 
                disabled={!hasPrevious} 
                onPress={onPrevious} 
            />
            <View style={styles.spacer} />
            <Text variant="labelLarge" style={{ color: theme.colors.onSurfaceVariant }}>
                {currentChapterIndex + 1} / {totalChapters}
            </Text>
            <View style={styles.spacer} />
            <Appbar.Action 
                icon="chevron-right" 
                disabled={!hasNext} 
                onPress={onNext} 
            />
        </Appbar>
    );
};

const styles = StyleSheet.create({
    bottomBar: {
        justifyContent: 'space-between',
        paddingHorizontal: 8,
    },
    spacer: {
        flex: 1,
    }
});
