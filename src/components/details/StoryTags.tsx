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
        <View testID="tags-container" style={styles.container}>
            {tags.map((tag, index) => (
                <Chip
                    testID={`chip-${index}`}
                    key={`${tag}-${index}`}
                    style={styles.chip}
                    textStyle={styles.chipText}
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
        gap: 8,
        paddingHorizontal: 16,
        marginBottom: 16,
    },
    chip: {
        height: 32,
    },
    chipText: {
        fontSize: 12,
        lineHeight: 20,
    }
});
