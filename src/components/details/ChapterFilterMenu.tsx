import React, { useState, useCallback, useRef } from 'react';
import { View, StyleSheet } from 'react-native';
import { Menu, IconButton, useTheme, Searchbar } from 'react-native-paper';
import { ChapterFilterMode } from '../../services/StorageService';

interface ChapterFilterMenuProps {
    filterMode: ChapterFilterMode;
    hasBookmark: boolean;
    onFilterSelect: (mode: ChapterFilterMode) => void;
    searchQuery: string;
    onSearchChange: (query: string) => void;
}

export const ChapterFilterMenu: React.FC<ChapterFilterMenuProps> = ({
    filterMode,
    hasBookmark,
    onFilterSelect,
    searchQuery,
    onSearchChange,
}) => {
    const theme = useTheme();
    const [visible, setVisible] = useState(false);
    const isClosingRef = useRef(false);

    const openMenu = useCallback(() => {
        if (isClosingRef.current) return;
        setVisible(true);
    }, []);

    const closeMenu = useCallback(() => {
        setVisible(false);
    }, []);

    const handleSelect = useCallback((mode: ChapterFilterMode) => {
        isClosingRef.current = true;
        setVisible(false);
        setTimeout(() => {
            onFilterSelect(mode);
            isClosingRef.current = false;
        }, 150);
    }, [onFilterSelect]);

    return (
        <Menu
            visible={visible}
            onDismiss={closeMenu}
            anchor={
                <Searchbar
                    placeholder="Search chapters"
                    onChangeText={onSearchChange}
                    value={searchQuery}
                    style={styles.searchbar}
                    inputStyle={styles.searchInput}
                    right={() => (
                        <View style={styles.rightContainer}>
                            <View style={[styles.separator, { backgroundColor: theme.colors.outlineVariant }]} />
                            <IconButton
                                icon="filter-variant"
                                onPress={openMenu}
                                size={20}
                                style={styles.filterButton}
                                testID="chapter-filter-button"
                            />
                        </View>
                    )}
                />
            }
        >
            <Menu.Item
                onPress={() => handleSelect('all')}
                title="Show all chapters"
                leadingIcon={filterMode === 'all' ? 'check' : undefined}
            />
            <Menu.Item
                onPress={() => handleSelect('hideNonDownloaded')}
                title="Hide non-downloaded"
                leadingIcon={filterMode === 'hideNonDownloaded' ? 'check' : undefined}
            />
            <Menu.Item
                onPress={() => handleSelect('hideAboveBookmark')}
                title="Hide chapters above bookmark"
                leadingIcon={filterMode === 'hideAboveBookmark' ? 'check' : undefined}
                disabled={!hasBookmark}
                titleStyle={!hasBookmark ? { color: theme.colors.outline } : undefined}
            />
        </Menu>
    );
};

const styles = StyleSheet.create({
    searchbar: {
        height: 48,
        alignItems: 'center',
    },
    searchInput: {
        minHeight: 0,
        alignSelf: 'center',
    },
    rightContainer: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    separator: {
        width: 1,
        height: 24,
        marginRight: 4,
    },
    filterButton: {
        margin: 0,
        marginRight: 4,
    },
});
