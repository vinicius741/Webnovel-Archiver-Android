import React from 'react';
import { List, useTheme } from 'react-native-paper';
import { Chapter } from '../../types';

interface ChapterListItemProps {
    item: Chapter;
    isLastRead?: boolean;
    onPress?: () => void;
    onLongPress?: () => void;
}

const sanitizeTitle = (title: string) => {
    // Remove trailing dots (standard ellipsis ... or Unicode …)
    return title.replace(/\s*(\.{3}|…)$/, '').trim();
};

export const ChapterListItem: React.FC<ChapterListItemProps> = ({ item, isLastRead, onPress, onLongPress }) => {
    const theme = useTheme();
    const sanitizedTitle = sanitizeTitle(item.title);
    
    return (
        <List.Item
            title={sanitizedTitle}
            titleStyle={[
                { fontSize: 16 },
                isLastRead ? { color: theme.colors.primary, fontWeight: 'bold' } : undefined
            ]}
            description={item.downloaded ? "Available Offline" : undefined}
            descriptionStyle={{ fontSize: 12, color: theme.colors.secondary }}
            left={props => (
                <List.Icon 
                    {...props} 
                    icon={isLastRead ? "bookmark" : (item.downloaded ? "file-check-outline" : "file-outline")} 
                    color={isLastRead ? theme.colors.primary : (item.downloaded ? theme.colors.secondary : theme.colors.outline)} 
                />
            )}
            right={props => item.downloaded ? (
                <List.Icon 
                    {...props} 
                    icon="check-circle-outline" 
                    color={theme.colors.secondary} 
                    style={{ marginVertical: 0, alignSelf: 'center' }}
                />
            ) : null}
            onPress={onPress}
            onLongPress={onLongPress}
            style={[
                { borderRadius: 8, marginVertical: 2, marginHorizontal: 8 },
                isLastRead ? { backgroundColor: theme.colors.primaryContainer + '30' } : undefined
            ]}
        />
    );
};
