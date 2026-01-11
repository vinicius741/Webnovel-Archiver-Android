import React, { useState } from 'react';
import { Menu, IconButton, Divider, useTheme } from 'react-native-paper';

interface StoryMenuProps {
    onApplySentenceRemoval: () => void;
    onDelete: () => void;
    disabled?: boolean;
}

export const StoryMenu: React.FC<StoryMenuProps> = ({
    onApplySentenceRemoval,
    onDelete,
    disabled = false
}) => {
    const theme = useTheme();
    const [visible, setVisible] = useState(false);

    const openMenu = () => setVisible(true);
    const closeMenu = () => setVisible(false);

    return (
        <Menu
            visible={visible}
            onDismiss={closeMenu}
            anchor={
                <IconButton 
                    icon="dots-vertical" 
                    onPress={openMenu}
                />
            }
        >
            <Menu.Item 
                onPress={() => {
                    closeMenu();
                    onApplySentenceRemoval();
                }} 
                title="Apply Sentence Removal"
                disabled={disabled}
            />
            <Divider />
            <Menu.Item 
                onPress={() => {
                    closeMenu();
                    onDelete();
                }} 
                title="Delete Novel"
                titleStyle={{ color: theme.colors.error }}
                disabled={disabled}
            />
        </Menu>
    );
};
