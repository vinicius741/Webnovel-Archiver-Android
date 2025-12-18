import React from 'react';
import { List, useTheme } from 'react-native-paper';
import { StyleSheet } from 'react-native';
import { Chapter } from '../../types';
import { sanitizeTitle } from '../../utils/stringUtils';

interface ChapterListItemProps {
    item: Chapter;
    isLastRead?: boolean;
    onPress?: () => void;
    onLongPress?: () => void;
}

export const ChapterListItem: React.FC<ChapterListItemProps> = ({ item, isLastRead, onPress, onLongPress }) => {
    const theme = useTheme();
    const sanitizedTitle = sanitizeTitle(item.title);
    
    return (
        <List.Item
            title={sanitizedTitle}
            titleStyle={[
                { fontSize: 15, fontWeight: '500' },
                isLastRead ? { color: theme.colors.primary, fontWeight: '700' } : { color: theme.colors.onSurface }
            ]}
            description={item.downloaded ? "Offline" : undefined}
            descriptionStyle={{ fontSize: 11, color: theme.colors.onSurfaceVariant }}
            left={props => (
                <List.Icon 
                    {...props} 
                    icon={isLastRead ? "bookmark" : (item.downloaded ? "check-decagram-outline" : "circle-outline")} 
                    color={isLastRead ? theme.colors.primary : (item.downloaded ? theme.colors.primary : theme.colors.outline)} 
                />
            )}
            onPress={onPress}
            onLongPress={onLongPress}
            style={[
                styles.item,
                isLastRead ? { backgroundColor: theme.colors.primaryContainer } : undefined
            ]}
        />
    );
};

const styles = StyleSheet.create({
    item: {
        borderRadius: 12,
        marginVertical: 1,
        marginHorizontal: 12,
        paddingVertical: 4,
    }
});
