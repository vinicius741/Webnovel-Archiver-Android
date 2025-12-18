import React, { useRef } from 'react';
import { View, StyleSheet } from 'react-native';
import { Text, useTheme } from 'react-native-paper';
import * as Clipboard from 'expo-clipboard';

interface StoryDescriptionProps {
    description?: string;
}

export const StoryDescription: React.FC<StoryDescriptionProps> = ({ description }) => {
    const theme = useTheme();
    const lastTap = useRef(0);

    if (!description) {
        return null;
    }

    const handlePress = async () => {
        const now = Date.now();
        const DOUBLE_PRESS_DELAY = 300;
        if (now - lastTap.current < DOUBLE_PRESS_DELAY) {
            await Clipboard.setStringAsync(description || '');
        }
        lastTap.current = now;
    };

    return (
        <View style={styles.descriptionContainer}>
            <Text 
                variant="bodyMedium" 
                style={[styles.description, { color: theme.colors.onSurfaceVariant }]}
                onPress={handlePress}
            >
                {description}
            </Text>
        </View>
    );
};

const styles = StyleSheet.create({
    descriptionContainer: {
        paddingHorizontal: 24,
        marginBottom: 24,
    },
    description: {
        textAlign: 'center',
        lineHeight: 22,
        fontWeight: '500',
    }
});
