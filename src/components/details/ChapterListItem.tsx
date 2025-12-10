import React from 'react';
import { List } from 'react-native-paper';
import { Chapter } from '../../types';

interface ChapterListItemProps {
    item: Chapter;
}

export const ChapterListItem: React.FC<ChapterListItemProps> = ({ item }) => (
    <List.Item
        title={item.title}
        left={props => <List.Icon {...props} icon="file-document-outline" />}
        onPress={() => {}}
    />
);
