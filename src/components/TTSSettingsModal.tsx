import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { Modal, Portal, Text, Button, useTheme, List, Divider, Searchbar } from 'react-native-paper';
import Slider from '@react-native-community/slider';
import * as Speech from 'expo-speech';
import { TTSSettings } from '../services/StorageService';

interface Props {
    visible: boolean;
    onDismiss: () => void;
    settings: TTSSettings;
    onSettingsChange: (settings: TTSSettings) => void;
}

const ModalContent = React.memo(({ settings, onSettingsChange, onDismiss, theme }: { settings: TTSSettings; onSettingsChange: (settings: TTSSettings) => void; onDismiss: () => void; theme: any }) => {
    const [voices, setVoices] = useState<Speech.Voice[]>([]);
    const [searchQuery, setSearchQuery] = useState('');
    const settingsRef = useRef(settings);

    useEffect(() => {
        settingsRef.current = settings;
    }, [settings]);

    useEffect(() => {
        const loadVoices = async () => {
            const availableVoices = await Speech.getAvailableVoicesAsync();
            setVoices(availableVoices.sort((a: Speech.Voice, b: Speech.Voice) => {
                if (a.quality === 'Enhanced' && b.quality !== 'Enhanced') return -1;
                if (a.quality !== 'Enhanced' && b.quality === 'Enhanced') return 1;
                return a.name.localeCompare(b.name);
            }));
        };
        loadVoices();
    }, []);

    const updateSetting = useCallback(<K extends keyof TTSSettings>(key: K, value: TTSSettings[K]) => {
        onSettingsChange({ ...settingsRef.current, [key]: value });
    }, [onSettingsChange]);

    const filteredVoices = useMemo(() =>
        voices.filter(voice =>
            voice.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
            voice.language.toLowerCase().includes(searchQuery.toLowerCase())
        ), [voices, searchQuery]
    );

    const primaryColor = theme.colors.primary;
    const surfaceVariantColor = theme.colors.surfaceVariant;

    const VoiceItem = useCallback(({ voice }: { voice: Speech.Voice }) => {
        const isSelected = settings.voiceIdentifier === voice.identifier;
        const icon = isSelected ? <List.Icon icon="check" color={primaryColor} /> : null;
        const style = isSelected ? { backgroundColor: surfaceVariantColor } : undefined;

        return (
            <List.Item
                key={voice.identifier}
                title={voice.name}
                description={`${voice.language} ${voice.quality === 'Enhanced' ? '(High Quality)' : ''}`}
                right={icon ? () => icon : undefined}
                onPress={() => updateSetting('voiceIdentifier', voice.identifier)}
                style={style}
            />
        );
    }, [settings.voiceIdentifier, primaryColor, surfaceVariantColor, updateSetting]);

    return (
        <>
            <Text variant="headlineSmall" style={styles.title}>TTS Settings</Text>

            <ScrollView style={styles.scroll}>
                <View style={styles.section}>
                    <Text variant="labelLarge">Pitch: {settings.pitch.toFixed(1)}</Text>
                    <Slider
                        style={styles.slider}
                        minimumValue={0.5}
                        maximumValue={2.0}
                        step={0.1}
                        value={settings.pitch}
                        onSlidingComplete={(val) => updateSetting('pitch', val)}
                        minimumTrackTintColor={theme.colors.primary}
                        maximumTrackTintColor={theme.colors.surfaceVariant}
                        thumbTintColor={theme.colors.primary}
                    />
                </View>

                <View style={styles.section}>
                    <Text variant="labelLarge">Rate: {settings.rate.toFixed(1)}</Text>
                    <Slider
                        style={styles.slider}
                        minimumValue={0.1}
                        maximumValue={2.0}
                        step={0.1}
                        value={settings.rate}
                        onSlidingComplete={(val) => updateSetting('rate', val)}
                        minimumTrackTintColor={theme.colors.primary}
                        maximumTrackTintColor={theme.colors.surfaceVariant}
                        thumbTintColor={theme.colors.primary}
                    />
                </View>

                <View style={styles.section}>
                    <Text variant="labelLarge">Chunk Size: {settings.chunkSize}</Text>
                    <Slider
                        style={styles.slider}
                        minimumValue={20}
                        maximumValue={1000}
                        step={50}
                        value={settings.chunkSize}
                        onSlidingComplete={(val) => updateSetting('chunkSize', Math.round(val))}
                        minimumTrackTintColor={theme.colors.primary}
                        maximumTrackTintColor={theme.colors.surfaceVariant}
                        thumbTintColor={theme.colors.primary}
                    />
                    <Text variant="bodySmall" style={styles.infoText}>
                        Larger chunks read more text at once but may be slower to start.
                    </Text>
                </View>

                <Divider style={styles.divider} />

                <View style={styles.voiceHeader}>
                    <Text variant="labelLarge" style={styles.sectionTitle}>Voice</Text>
                    <Searchbar
                        placeholder="Search voices..."
                        onChangeText={setSearchQuery}
                        value={searchQuery}
                        style={styles.searchbar}
                        inputStyle={styles.searchbarInput}
                    />
                </View>

                {voices.length === 0 && (
                    <Text variant="bodyMedium" style={styles.infoText}>Loading voices...</Text>
                )}
                {filteredVoices.map((voice) => (
                    <VoiceItem key={voice.identifier} voice={voice} />
                ))}
            </ScrollView>

            <Button mode="contained" onPress={onDismiss} style={styles.closeButton}>
                Done
            </Button>
        </>
    );
});

export const TTSSettingsModal: React.FC<Props> = ({ visible, onDismiss, settings, onSettingsChange }) => {
    const theme = useTheme();

    const handleSettingsChange = useCallback((newSettings: TTSSettings) => {
        onSettingsChange(newSettings);
    }, [onSettingsChange]);

    return (
        <Modal
            visible={visible}
            onDismiss={onDismiss}
            contentContainerStyle={[styles.modalContent, { backgroundColor: theme.colors.surface }]}
        >
            <ModalContent
                settings={settings}
                onSettingsChange={handleSettingsChange}
                onDismiss={onDismiss}
                theme={theme}
            />
        </Modal>
    );
};

const styles = StyleSheet.create({
    modalContent: {
        margin: 20,
        padding: 20,
        borderRadius: 12,
        maxHeight: '85%',
        elevation: 5,
    },
    title: {
        marginBottom: 16,
        fontWeight: 'bold',
    },
    scroll: {
        marginBottom: 16,
    },
    section: {
        marginBottom: 20,
    },
    sectionTitle: {
        marginTop: 8,
        marginBottom: 8,
        fontWeight: 'bold',
    },
    slider: {
        width: '100%',
        height: 40,
    },
    divider: {
        marginVertical: 12,
    },
    closeButton: {
        marginTop: 8,
    },
    infoText: {
        opacity: 0.7,
        fontStyle: 'italic',
        marginVertical: 8,
    },
    voiceHeader: {
        marginTop: 8,
        marginBottom: 8,
    },
    searchbar: {
        marginVertical: 8,
        height: 45,
        elevation: 0,
        backgroundColor: 'rgba(0,0,0,0.05)',
    },
    searchbarInput: {
        minHeight: 0,
    }
});
