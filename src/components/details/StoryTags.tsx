import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Chip, Text, useTheme } from 'react-native-paper';

interface StoryTagsProps {
    tags?: string[];
}

export const StoryTags: React.FC<StoryTagsProps> = ({ tags }) => {
    const theme = useTheme();

    if (!tags || tags.length === 0) {
        return null;
    }

    return (
        <View style={styles.container}>
            {tags.map((tag, index) => (
                <Chip 
                    key={`${tag}-${index}`} 
                    style={[styles.chip, { backgroundColor: theme.colors.primaryContainer }]} 
                    textStyle={[styles.chipText, { color: theme.colors.onPrimaryContainer }]}
                    compact
                >
                    {tag}
                </Chip>
            ))}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        justifyContent: 'center',
        gap: 6,
        paddingHorizontal: 16,
        marginBottom: 20,
    },
    chip: {
        height: 26,
        borderRadius: 6,
    },
    chipText: {
        fontSize: 10,
        fontWeight: 'bold',
    }
});
