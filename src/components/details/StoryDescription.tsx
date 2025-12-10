import React, { useRef } from 'react';
import { View, StyleSheet } from 'react-native';
import { Text } from 'react-native-paper';
import * as Clipboard from 'expo-clipboard';

interface StoryDescriptionProps {
    description?: string;
}

export const StoryDescription: React.FC<StoryDescriptionProps> = ({ description }) => {
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
                style={styles.description}
                onPress={handlePress}
            >
                {description}
            </Text>
        </View>
    );
};

const styles = StyleSheet.create({
    descriptionContainer: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        marginBottom: 20,
        paddingHorizontal: 16,
    },
    description: {
        flex: 1,
        textAlign: 'center',
    }
});
