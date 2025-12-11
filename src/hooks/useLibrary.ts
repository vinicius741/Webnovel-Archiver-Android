import { useState, useCallback, useMemo } from 'react';
import { useFocusEffect } from 'expo-router';
import { storageService } from '../services/StorageService';
import { Story } from '../types';
import { sourceRegistry } from '../services/source/SourceRegistry';

export type SortOption = 'default' | 'title' | 'dateAdded' | 'lastUpdated' | 'totalChapters';
export type SortDirection = 'asc' | 'desc';

export const useLibrary = () => {
    const [stories, setStories] = useState<Story[]>([]);
    const [searchQuery, setSearchQuery] = useState('');
    const [selectedTags, setSelectedTags] = useState<string[]>([]);
    const [refreshing, setRefreshing] = useState(false);
    
    // Sorting state
    const [sortOption, setSortOption] = useState<SortOption>('default');
    const [sortDirection, setSortDirection] = useState<SortDirection>('desc');

    const loadLibrary = async () => {
        try {
            setRefreshing(true);
            const library = await storageService.getLibrary();
            // We'll handle sorting in the useMemo below, so we just set raw data here
            // But to keep initial state consistent or if useMemo is expensive, we could sort here.
            // However, dynamic sorting is requested.
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

    const { allTags, sourceNames } = useMemo(() => {
        const sources = new Set<string>();
        // Always collect all sources first to allow switching
        stories.forEach(story => {
            const providerName = sourceRegistry.getProvider(story.sourceUrl)?.name;
            if (providerName) {
                sources.add(providerName);
            }
        });
        const sortedSources = Array.from(sources).sort();

        // Determine if a source is currently selected
        const activeSource = selectedTags.find(tag => sortedSources.includes(tag));

        const tags = new Set<string>();
        stories.forEach(story => {
            const providerName = sourceRegistry.getProvider(story.sourceUrl)?.name;
            
            // If a source is selected, only collect tags from stories of that source
            if (activeSource && providerName !== activeSource) {
                return;
            }

            story.tags?.forEach(tag => tags.add(tag));
        });

        const sortedTags = Array.from(tags).sort();

        return {
            allTags: [...sortedSources, ...sortedTags],
            sourceNames: sortedSources
        };
    }, [stories, selectedTags]);

    const toggleTag = (tag: string) => {
        setSelectedTags(prev => {
            const isSourceTag = sourceNames.includes(tag);
            
            if (isSourceTag) {
                // If it's already selected, toggle it off
                if (prev.includes(tag)) {
                    return prev.filter(t => t !== tag);
                }
                
                // If adding a source tag, remove any other source tags from selection first
                const nonSourceTags = prev.filter(t => !sourceNames.includes(t));
                return [...nonSourceTags, tag];
            }
            
            // Standard toggle for regular tags
            return prev.includes(tag) ? prev.filter(t => t !== tag) : [...prev, tag];
        });
    };

    const filteredAndSortedStories = useMemo(() => {
        // First filter
        const filtered = stories.filter(story => {
            const matchesSearch = story.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
                (story.author && story.author.toLowerCase().includes(searchQuery.toLowerCase()));

            if (!matchesSearch) return false;

            if (selectedTags.length > 0) {
                const storyTags = story.tags || [];
                const storySourceName = sourceRegistry.getProvider(story.sourceUrl)?.name;

                const hasAllTags = selectedTags.every(tag => {
                    // Check if the selected tag is actually a source name
                    if (sourceNames.includes(tag)) {
                        return storySourceName === tag;
                    }
                    return storyTags.includes(tag);
                });
                return hasAllTags;
            }

            return true;
        });

        // Then sort
        return filtered.sort((a, b) => {
            let comparison = 0;

            switch (sortOption) {
                case 'title':
                    comparison = a.title.localeCompare(b.title);
                    break;
                case 'dateAdded':
                    comparison = (a.dateAdded || 0) - (b.dateAdded || 0);
                    break;
                case 'lastUpdated':
                    comparison = (a.lastUpdated || 0) - (b.lastUpdated || 0);
                    break;
                case 'totalChapters':
                    comparison = a.totalChapters - b.totalChapters;
                    break;
                case 'default':
                default:
                    // Default logic: Smart sort by recent activity (max of lastUpdated or dateAdded)
                    const dateA = Math.max(a.lastUpdated || 0, a.dateAdded || 0);
                    const dateB = Math.max(b.lastUpdated || 0, b.dateAdded || 0);
                    comparison = dateA - dateB;
                    break;
            }

            // Apply direction (Ascending is default for numbers in subtraction a-b, but we want Descending usually for dates)
            // Wait, standard sort:
            // a - b is Ascending (Small to Large)
            // b - a is Descending (Large to Small)
            
            // For strings (localeCompare): 'a'.localeCompare('b') is -1 (Ascending)
            
            // So 'comparison' above is calculated as Ascending (except default which we might want to verify).
            // Actually, for 'default', I calculated dateA - dateB.
            // If dateA (today) > dateB (yesterday), dateA - dateB > 0.
            // In Ascending sort, positive means b comes first? No.
            // sort((a,b) => a-b):
            // 2 - 1 = 1 (>0), so b (1) comes before a (2). Sorted: 1, 2. ASC.
            
            // So my calculations above are all ASCENDING.
            
            // If user wants ASC, we return comparison.
            // If user wants DESC, we return -comparison.
            
            return sortDirection === 'asc' ? comparison : -comparison;
        });
    }, [stories, searchQuery, selectedTags, sortOption, sortDirection, sourceNames]);

    return {
        stories: filteredAndSortedStories,
        allStories: stories,
        loading: refreshing,
        refreshing,
        onRefresh,
        searchQuery,
        setSearchQuery,
        selectedTags,
        toggleTag,
        allTags,
        sortOption,
        setSortOption,
        sortDirection,
        setSortDirection
    };
};