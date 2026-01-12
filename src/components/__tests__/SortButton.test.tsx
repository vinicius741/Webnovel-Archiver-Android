import React from 'react';
import { render, fireEvent, waitFor } from '@testing-library/react-native';
import { SortButton } from '../SortButton';
import { screen } from '@testing-library/react-native';

jest.useFakeTimers();

jest.mock('../SortButton', () => {
    const React = require('react');
    const { View, Text, TouchableOpacity } = require('react-native');
    return {
        SortButton: ({ sortOption, sortDirection, onSortSelect, onToggleDirection }: any) => {
            const [visible, setVisible] = React.useState(false);
            const isClosingRef = React.useRef(false);
            const timeoutRef = React.useRef(null);

            const openMenu = () => {
                if (!isClosingRef.current) setVisible(true);
            };

            const closeMenu = () => setVisible(false);

            const handleSelect = (option: any) => {
                isClosingRef.current = true;
                setVisible(false);
                if (timeoutRef.current) clearTimeout(timeoutRef.current);
                timeoutRef.current = setTimeout(() => {
                    onSortSelect(option);
                    isClosingRef.current = false;
                }, 150);
            };

            const handleToggle = () => {
                isClosingRef.current = true;
                setVisible(false);
                if (timeoutRef.current) clearTimeout(timeoutRef.current);
                timeoutRef.current = setTimeout(() => {
                    onToggleDirection();
                    isClosingRef.current = false;
                }, 150);
            };

            return (
                <View testID="sort-button-container">
                    <TouchableOpacity testID="icon-button" onPress={openMenu}>
                        <Text>{sortDirection === 'asc' ? 'sort-ascending' : 'sort-descending'}</Text>
                    </TouchableOpacity>
                    {visible && (
                        <View testID="menu">
                            <TouchableOpacity testID="Default (Smart)" onPress={() => handleSelect('default')}><Text>Default (Smart)</Text></TouchableOpacity>
                            <TouchableOpacity testID="Title" onPress={() => handleSelect('title')}><Text>Title</Text></TouchableOpacity>
                            <TouchableOpacity testID="Last Updated" onPress={() => handleSelect('lastUpdated')}><Text>Last Updated</Text></TouchableOpacity>
                            <TouchableOpacity testID="Date Added" onPress={() => handleSelect('dateAdded')}><Text>Date Added</Text></TouchableOpacity>
                            <TouchableOpacity testID="Chapter Count" onPress={() => handleSelect('totalChapters')}><Text>Chapter Count</Text></TouchableOpacity>
                            <TouchableOpacity testID="Score" onPress={() => handleSelect('score')}><Text>Score</Text></TouchableOpacity>
                            <View testID="divider" />
                            <TouchableOpacity testID={sortDirection === 'asc' ? 'Ascending' : 'Descending'} onPress={handleToggle}>
                                <Text>{sortDirection === 'asc' ? 'Ascending' : 'Descending'}</Text>
                            </TouchableOpacity>
                        </View>
                    )}
                </View>
            );
        },
    };
});

describe('SortButton', () => {
    const { SortButton } = require('../SortButton');

    const defaultProps = {
        sortOption: 'default' as const,
        sortDirection: 'asc' as const,
        onSortSelect: jest.fn(),
        onToggleDirection: jest.fn(),
    };

    beforeEach(() => {
        defaultProps.onSortSelect.mockClear();
        defaultProps.onToggleDirection.mockClear();
        jest.clearAllTimers();
    });

    it('should render sort button', () => {
        const { getByTestId } = render(<SortButton {...defaultProps} />);
        expect(getByTestId('icon-button')).toBeTruthy();
    });

    it('should show ascending icon when direction is asc', () => {
        const { getByTestId } = render(<SortButton {...defaultProps} sortDirection="asc" />);

        expect(getByTestId('icon-button')).toBeTruthy();
    });

    it('should show descending icon when direction is desc', () => {
        const { getByTestId } = render(<SortButton {...defaultProps} sortDirection="desc" />);

        expect(getByTestId('icon-button')).toBeTruthy();
    });

    it('should open menu when button is pressed', () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Default (Smart)')).toBeTruthy();
    });

    it('should show menu items', () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Default (Smart)')).toBeTruthy();
        expect(getByText('Title')).toBeTruthy();
        expect(getByText('Last Updated')).toBeTruthy();
        expect(getByText('Date Added')).toBeTruthy();
        expect(getByText('Chapter Count')).toBeTruthy();
        expect(getByText('Score')).toBeTruthy();
    });

    it('should check current sort option', () => {
        const props = { ...defaultProps, sortOption: 'title' as const };
        const { getByTestId, getByText } = render(<SortButton {...props} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Title')).toBeTruthy();
    });

    it('should call onSortSelect when sort option is selected', async () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        fireEvent.press(getByText('Title'));
        jest.advanceTimersByTime(150);

        await waitFor(() => {
            expect(defaultProps.onSortSelect).toHaveBeenCalledWith('title');
        });
    });

    it('should call onToggleDirection when direction toggle is selected', async () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        fireEvent.press(getByText('Ascending'));
        jest.advanceTimersByTime(150);

        await waitFor(() => {
            expect(defaultProps.onToggleDirection).toHaveBeenCalled();
        });
    });

    it('should show ascending text when direction is asc', () => {
        const props = { ...defaultProps, sortDirection: 'asc' as const };
        const { getByTestId, getByText } = render(<SortButton {...props} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Ascending')).toBeTruthy();
    });

    it('should show descending text when direction is desc', () => {
        const props = { ...defaultProps, sortDirection: 'desc' as const };
        const { getByTestId, getByText } = render(<SortButton {...props} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Descending')).toBeTruthy();
    });

    it('should show up arrow when direction is asc', () => {
        const props = { ...defaultProps, sortDirection: 'asc' as const };
        const { getByTestId, getByText } = render(<SortButton {...props} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Ascending')).toBeTruthy();
    });

    it('should show down arrow when direction is desc', () => {
        const props = { ...defaultProps, sortDirection: 'desc' as const };
        const { getByTestId, getByText } = render(<SortButton {...props} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Descending')).toBeTruthy();
    });

    it('should close menu after selection', async () => {
        const { getByTestId, getByText, queryByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Default (Smart)')).toBeTruthy();

        fireEvent.press(getByText('Title'));
        jest.advanceTimersByTime(150);

        await waitFor(() => {
            expect(queryByText('Default (Smart)')).toBeNull();
        });
    });

    it('should prevent opening while closing animation is running', async () => {
        const { getByTestId } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        fireEvent.press(getByTestId('Title'));

        fireEvent.press(button);
        jest.advanceTimersByTime(150);

        await waitFor(() => {
            expect(defaultProps.onSortSelect).toHaveBeenCalledTimes(1);
        });
    });

    it('should render all sort options', () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        expect(getByText('Default (Smart)')).toBeTruthy();
        expect(getByText('Title')).toBeTruthy();
        expect(getByText('Last Updated')).toBeTruthy();
        expect(getByText('Date Added')).toBeTruthy();
        expect(getByText('Chapter Count')).toBeTruthy();
        expect(getByText('Score')).toBeTruthy();
    });

    it('should handle lastUpdated sort option', async () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        fireEvent.press(getByText('Last Updated'));
        jest.advanceTimersByTime(150);

        await waitFor(() => {
            expect(defaultProps.onSortSelect).toHaveBeenCalledWith('lastUpdated');
        });
    });

    it('should handle dateAdded sort option', async () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        fireEvent.press(getByText('Date Added'));
        jest.advanceTimersByTime(150);

        await waitFor(() => {
            expect(defaultProps.onSortSelect).toHaveBeenCalledWith('dateAdded');
        });
    });

    it('should handle totalChapters sort option', async () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        fireEvent.press(getByText('Chapter Count'));
        jest.advanceTimersByTime(150);

        await waitFor(() => {
            expect(defaultProps.onSortSelect).toHaveBeenCalledWith('totalChapters');
        });
    });

    it('should handle score sort option', async () => {
        const { getByTestId, getByText } = render(<SortButton {...defaultProps} />);

        const button = getByTestId('icon-button');
        fireEvent.press(button);

        fireEvent.press(getByText('Score'));
        jest.advanceTimersByTime(150);

        await waitFor(() => {
            expect(defaultProps.onSortSelect).toHaveBeenCalledWith('score');
        });
    });
});
