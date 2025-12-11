import React from 'react';
import { List } from 'react-native-paper';
import { Chapter } from '../../types';

import { useTheme } from 'react-native-paper';

interface ChapterListItemProps {
    item: Chapter;
    isLastRead?: boolean;
    onLongPress?: () => void;
}

export const ChapterListItem: React.FC<ChapterListItemProps> = ({ item, isLastRead, onLongPress }) => {
    const theme = useTheme();
    
    return (
        <List.Item
            title={item.title}
            titleStyle={isLastRead ? { color: theme.colors.primary, fontWeight: 'bold' } : undefined}
            description={isLastRead ? "Last Read" : undefined}
            descriptionStyle={{ color: theme.colors.primary }}
            left={props => <List.Icon {...props} icon={isLastRead ? "bookmark" : "file-document-outline"} color={isLastRead ? theme.colors.primary : undefined} />}
            onPress={() => {}}
            onLongPress={onLongPress}
            style={isLastRead ? { backgroundColor: theme.colors.primaryContainer + '20' } : undefined}
        />
    );
};
