import { useState, useCallback, useMemo } from 'react';
import { useFocusEffect } from 'expo-router';
import { storageService } from '../services/StorageService';
import { Story } from '../types';

export const useLibrary = () => {
    const [stories, setStories] = useState<Story[]>([]);
    const [searchQuery, setSearchQuery] = useState('');
    const [selectedTags, setSelectedTags] = useState<string[]>([]);
    const [refreshing, setRefreshing] = useState(false);

    const loadLibrary = async () => {
        try {
            setRefreshing(true);
            const library = await storageService.getLibrary();
            library.sort((a, b) => {
                const dateA = Math.max(a.lastUpdated || 0, a.dateAdded || 0);
                const dateB = Math.max(b.lastUpdated || 0, b.dateAdded || 0);
                return dateB - dateA;
            });

            setStories(library);
        } catch (e) {
            console.error(e);
        } finally {
            setRefreshing(false);
        }
    };

    useFocusEffect(
        useCallback(() => {
            loadLibrary();
        }, [])
    );

    const onRefresh = useCallback(() => {
        loadLibrary();
    }, []);

    const allTags = useMemo(() => {
        const tags = new Set<string>();
        stories.forEach(story => {
            story.tags?.forEach(tag => tags.add(tag));
        });
        return Array.from(tags).sort();
    }, [stories]);

    const toggleTag = (tag: string) => {
        setSelectedTags(prev =>
            prev.includes(tag) ? prev.filter(t => t !== tag) : [...prev, tag]
        );
    };

    const filteredStories = useMemo(() => {
        return stories.filter(story => {
            const matchesSearch = story.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
                (story.author && story.author.toLowerCase().includes(searchQuery.toLowerCase()));

            if (!matchesSearch) return false;

            if (selectedTags.length > 0) {
                const storyTags = story.tags || [];
                // Check if story has ALL selected tags (AND logic)
                const hasAllTags = selectedTags.every(tag => storyTags.includes(tag));
                return hasAllTags;
            }

            return true;
        });
    }, [stories, searchQuery, selectedTags]);

    return {
        stories: filteredStories, // Return filtered stories as primary 'stories'
        allStories: stories, // Return all stories if needed
        loading: refreshing,
        refreshing,
        onRefresh,
        searchQuery,
        setSearchQuery,
        selectedTags,
        toggleTag,
        allTags,
    };
};
