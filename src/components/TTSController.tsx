import React from 'react';
import { View, StyleSheet, Animated } from 'react-native';
import { Surface, IconButton, Text, useTheme } from 'react-native-paper';

interface TTSControllerProps {
    onPlayPause: () => void;
    onStop: () => void;
    onNext: () => void;
    onPrevious: () => void;
    isSpeaking: boolean;
    isPaused: boolean;
    currentChunk: number;
    totalChunks: number;
    visible: boolean;
}

export const TTSController: React.FC<TTSControllerProps> = ({
    onPlayPause,
    onStop,
    onNext,
    onPrevious,
    isSpeaking,
    isPaused,
    currentChunk,
    totalChunks,
    visible
}) => {
    const theme = useTheme();
    const [fadeAnim] = React.useState(new Animated.Value(0));

    const [isRendered, setIsRendered] = React.useState(visible);

    React.useEffect(() => {
        if (visible) {
            setIsRendered(true);
            Animated.timing(fadeAnim, {
                toValue: 1,
                duration: 300,
                useNativeDriver: true,
            }).start();
        } else {
            Animated.timing(fadeAnim, {
                toValue: 0,
                duration: 300,
                useNativeDriver: true,
            }).start(({ finished }) => {
                if (finished) setIsRendered(false);
            });
        }
    }, [visible]);

    if (!isRendered) return null;

    return (
        <Animated.View style={[styles.container, { opacity: fadeAnim }]}>
            <Surface style={[styles.surface, { backgroundColor: theme.colors.elevation.level3 }]} elevation={4}>
                <View style={styles.content}>
                    <View style={styles.info}>
                        <Text variant="labelSmall" style={{ color: theme.colors.primary }}>
                            TEXT-TO-SPEECH
                        </Text>
                        <Text variant="bodySmall">
                            {totalChunks > 0 ? `Chunk ${currentChunk + 1} / ${totalChunks}` : 'Initializing...'}
                        </Text>
                    </View>

                    <View style={styles.controls}>
                        <IconButton
                            icon="skip-previous"
                            size={24}
                            disabled={currentChunk === 0}
                            onPress={onPrevious}
                        />
                        <IconButton
                            icon={isPaused ? "play" : "pause"}
                            size={32}
                            mode="contained"
                            containerColor={theme.colors.primary}
                            iconColor={theme.colors.onPrimary}
                            onPress={onPlayPause}
                        />
                        <IconButton
                            icon="skip-next"
                            size={24}
                            disabled={currentChunk >= totalChunks - 1}
                            onPress={onNext}
                        />
                        <IconButton
                            icon="stop"
                            size={24}
                            onPress={onStop}
                        />
                    </View>
                </View>
            </Surface>
        </Animated.View>
    );
};

const styles = StyleSheet.create({
    container: {
        position: 'absolute',
        bottom: 120, // Above the bottom bar
        left: 16,
        right: 16,
        zIndex: 1000,
    },
    surface: {
        borderRadius: 24,
        paddingHorizontal: 16,
        paddingVertical: 8,
    },
    content: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    info: {
        flex: 1,
    },
    controls: {
        flexDirection: 'row',
        alignItems: 'center',
    },
});
