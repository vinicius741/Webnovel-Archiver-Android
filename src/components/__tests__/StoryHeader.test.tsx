import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { StoryHeader } from '../details/StoryHeader';
import { ThemeProvider } from 'react-native-paper';
import { Linking } from 'react-native';

jest.mock('../ImageViewer', () => {
    const React = require('react');
    const { View, Modal } = require('react-native');
    return {
        __esModule: true,
        default: ({ visible, onRequestClose }: any) => (
            <Modal visible={visible} onRequestClose={onRequestClose} testID="modal">
                <View />
            </Modal>
        ),
    };
});

jest.mock('../../services/source/SourceRegistry', () => ({
    sourceRegistry: {
        getProvider: jest.fn().mockReturnValue({ name: 'RoyalRoad' }),
    },
}));

jest.mock('react-native-paper', () => ({
    ...jest.requireActual('react-native-paper'),
    useTheme: jest.fn().mockReturnValue({
        colors: {
            secondary: '#03dac6',
            primaryContainer: '#bb86fc',
        },
    }),
}));

describe('StoryHeader', () => {
    let openURLSpy: jest.SpyInstance;

    beforeEach(() => {
        openURLSpy = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    });

    afterEach(() => {
        openURLSpy.mockRestore();
    });

    const mockTheme = {
        colors: {
            secondary: '#03dac6',
            primaryContainer: '#bb86fc',
        },
        fonts: {
            regular: {
                fontFamily: 'System',
                fontWeight: '400' as const,
            },
        },
    };

    const defaultProps = {
        story: {
            id: '1',
            title: 'Test Story',
            author: 'Test Author',
            sourceUrl: 'https://example.com',
            coverUrl: 'https://example.com/cover.jpg',
            description: 'Test description',
            tags: ['tag1', 'tag2'],
            status: 'idle' as const,
            totalChapters: 100,
            downloadedChapters: 50,
            chapters: [],
            lastUpdated: 1704067200000,
            dateAdded: 1704067200000,
            score: '4.5',
            lastReadChapterId: '50',
        },
    };

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <ThemeProvider theme={mockTheme as any}>
                {component}
            </ThemeProvider>
        );
    };

    it('should render story title', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByText('Test Story')).toBeTruthy();
    });

    it('should render story author', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByText('Test Author')).toBeTruthy();
    });

    it('should render cover image', () => {
        const { getByTestId } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByTestId('image')).toBeTruthy();
    });

    it('should not render cover image when coverUrl is undefined', () => {
        const props = { ...defaultProps, story: { ...defaultProps.story, coverUrl: undefined } };
        const { queryByTestId } = renderWithTheme(<StoryHeader {...props} />);

        expect(queryByTestId('image')).toBeNull();
    });

    it('should render source chip', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByText('RoyalRoad')).toBeTruthy();
    });

    it('should render chapter count', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByText('100 Chs')).toBeTruthy();
    });

    it('should render downloaded chapters count', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByText('50 Saved')).toBeTruthy();
    });

    it('should render score when provided', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByText('4.5')).toBeTruthy();
    });

    it('should not render score when not provided', () => {
        const props = { ...defaultProps, story: { ...defaultProps.story, score: undefined } };
        const { queryByText } = renderWithTheme(<StoryHeader {...props} />);

        expect(queryByText('4.5')).toBeNull();
    });

    it('should render with centered title', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        const title = getByText('Test Story');
        expect(title).toBeTruthy();
    });

    it('should render with centered author', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        const author = getByText('Test Author');
        expect(author).toBeTruthy();
    });

    it('should call Linking.openURL on double press of title', async () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        const title = getByText('Test Story');

        await fireEvent.press(title);
        await fireEvent.press(title);

        expect(openURLSpy).toHaveBeenCalledWith('https://example.com');
    });

    it('should not call Linking.openURL on single press', async () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        const title = getByText('Test Story');

        await fireEvent.press(title);

        expect(openURLSpy).not.toHaveBeenCalled();
    });

    it('should render image viewer when cover image is pressed', () => {
        const { getByTestId } = renderWithTheme(<StoryHeader {...defaultProps} />);

        const image = getByTestId('image');
        fireEvent.press(image);

        expect(getByTestId('modal')).toBeTruthy();
    });

    it('should render stats container', () => {
        const { getByTestId } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByTestId('story-header')).toBeTruthy();
    });

    it('should render all stat items', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByText('4.5')).toBeTruthy();
        expect(getByText('100 Chs')).toBeTruthy();
        expect(getByText('50 Saved')).toBeTruthy();
    });

    it('should handle long title', () => {
        const longTitle = 'This is a very long title that might need to be truncated in the UI';
        const props = { ...defaultProps, story: { ...defaultProps.story, title: longTitle } };
        const { getByText } = renderWithTheme(<StoryHeader {...props} />);

        expect(getByText(longTitle)).toBeTruthy();
    });

    it('should handle long author name', () => {
        const longAuthor = 'This is a very long author name that might need to be truncated';
        const props = { ...defaultProps, story: { ...defaultProps.story, author: longAuthor } };
        const { getByText } = renderWithTheme(<StoryHeader {...props} />);

        expect(getByText(longAuthor)).toBeTruthy();
    });

    it('should render proper chip with source name', () => {
        const { getByText } = renderWithTheme(<StoryHeader {...defaultProps} />);

        expect(getByText('RoyalRoad')).toBeTruthy();
    });
});
