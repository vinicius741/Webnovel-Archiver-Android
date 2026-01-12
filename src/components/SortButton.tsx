import React, { useState, useCallback, useRef } from 'react';
import { View, InteractionManager } from 'react-native';
import { Menu, IconButton, Divider } from 'react-native-paper';
import { SortOption, SortDirection } from '../hooks/useLibrary';

interface SortButtonProps {
    sortOption: SortOption;
    sortDirection: SortDirection;
    onSortSelect: (option: SortOption) => void;
    onToggleDirection: () => void;
}

export const SortButton: React.FC<SortButtonProps> = ({ 
    sortOption, 
    sortDirection, 
    onSortSelect,
    onToggleDirection 
}) => {
    const [visible, setVisible] = useState(false);
    const isClosingRef = useRef(false);

    const openMenu = useCallback(() => {
        // Prevent opening while closing animation is running
        if (isClosingRef.current) return;
        setVisible(true);
    }, []);
    
    const closeMenu = useCallback(() => {
        setVisible(false);
    }, []);

    const handleSelect = useCallback((option: SortOption) => {
        isClosingRef.current = true;
        setVisible(false);
        // Delay state update to allow menu animation to complete
        setTimeout(() => {
            onSortSelect(option);
            isClosingRef.current = false;
        }, 150);
    }, [onSortSelect]);

    const handleToggle = useCallback(() => {
        isClosingRef.current = true;
        setVisible(false);
        // Delay state update to allow menu animation to complete
        setTimeout(() => {
            onToggleDirection();
            isClosingRef.current = false;
        }, 150);
    }, [onToggleDirection]);

    return (
        <View>
            <Menu
                visible={visible}
                onDismiss={closeMenu}
                anchor={
                    <IconButton
                        icon={sortDirection === 'asc' ? 'sort-ascending' : 'sort-descending'}
                        onPress={openMenu}
                        mode="contained-tonal"
                        style={{ margin: 0 }}
                        testID="icon-button"
                    />
                }
            >
                <Menu.Item 
                    onPress={() => handleSelect('default')} 
                    title="Default (Smart)" 
                    leadingIcon={sortOption === 'default' ? 'check' : undefined}
                />
                <Menu.Item 
                    onPress={() => handleSelect('title')} 
                    title="Title" 
                    leadingIcon={sortOption === 'title' ? 'check' : undefined}
                />
                <Menu.Item 
                    onPress={() => handleSelect('lastUpdated')} 
                    title="Last Updated" 
                    leadingIcon={sortOption === 'lastUpdated' ? 'check' : undefined}
                />
                <Menu.Item 
                    onPress={() => handleSelect('dateAdded')} 
                    title="Date Added" 
                    leadingIcon={sortOption === 'dateAdded' ? 'check' : undefined}
                />
                 <Menu.Item 
                    onPress={() => handleSelect('totalChapters')} 
                    title="Chapter Count" 
                    leadingIcon={sortOption === 'totalChapters' ? 'check' : undefined}
                />
                <Menu.Item 
                    onPress={() => handleSelect('score')} 
                    title="Score" 
                    leadingIcon={sortOption === 'score' ? 'check' : undefined}
                />
                <Divider />
                <Menu.Item 
                    onPress={handleToggle} 
                    title={sortDirection === 'asc' ? 'Ascending' : 'Descending'}
                    trailingIcon={sortDirection === 'asc' ? 'arrow-up' : 'arrow-down'}
                />
            </Menu>
        </View>
    );
};