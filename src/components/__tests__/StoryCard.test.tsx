import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { StoryCard } from '../StoryCard';
import { ThemeProvider } from 'react-native-paper';

jest.mock('react-native-paper', () => ({
    ...jest.requireActual('react-native-paper'),
    useTheme: jest.fn().mockReturnValue({
        colors: {
            surface: '#ffffff',
            onSurface: '#000000',
            onSurfaceVariant: '#666666',
            primary: '#6200ee',
        },
    }),
}));

describe('StoryCard', () => {
    const mockTheme = {
        colors: {
            surface: '#ffffff',
            onSurface: '#000000',
            onSurfaceVariant: '#666666',
            primary: '#6200ee',
        },
        animation: {
            scale: 1,
        },
    };

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <ThemeProvider theme={mockTheme as any}>
                {component}
            </ThemeProvider>
        );
    };

    const defaultProps = {
        title: 'Test Story',
        author: 'Test Author',
        onPress: jest.fn(),
    };

    it('should render title and author', () => {
        const { getByText } = renderWithTheme(<StoryCard {...defaultProps} />);

        expect(getByText('Test Story')).toBeTruthy();
        expect(getByText('Test Author')).toBeTruthy();
    });

    it('should call onPress when card is pressed', () => {
        const { getByTestId } = renderWithTheme(<StoryCard {...defaultProps} />);

        const card = getByTestId('story-card');
        fireEvent.press(card);
        expect(defaultProps.onPress).toHaveBeenCalledTimes(1);
    });

    it('should render cover image when coverUrl is provided', () => {
        const { getByTestId } = renderWithTheme(
            <StoryCard {...defaultProps} coverUrl="https://example.com/cover.jpg" />
        );

        expect(getByTestId('story-card-cover')).toBeTruthy();
    });

    it('should not render cover image when coverUrl is not provided', () => {
        const { queryByTestId } = renderWithTheme(<StoryCard {...defaultProps} />);

        expect(queryByTestId('story-card-cover')).toBeNull();
    });

    it('should render source name when provided', () => {
        const { getByText } = renderWithTheme(
            <StoryCard {...defaultProps} sourceName="RoyalRoad" />
        );

        expect(getByText('RoyalRoad')).toBeTruthy();
    });

    it('should not render source name when not provided', () => {
        const { queryByText } = renderWithTheme(<StoryCard {...defaultProps} />);

        expect(queryByText('RoyalRoad')).toBeNull();
    });

    it('should render score when provided', () => {
        const { getByText, getByTestId } = renderWithTheme(
            <StoryCard {...defaultProps} score="4.5" />
        );

        expect(getByText('4.5')).toBeTruthy();
        expect(getByTestId('story-card-score-icon')).toBeTruthy();
    });

    it('should not render score when not provided', () => {
        const { queryByText } = renderWithTheme(<StoryCard {...defaultProps} />);

        expect(queryByText('4.5')).toBeNull();
    });

    it('should render last read chapter when provided', () => {
        const { getByText } = renderWithTheme(
            <StoryCard {...defaultProps} lastReadChapterName="Chapter 10" />
        );

        expect(getByText(/Last read: Chapter 10/)).toBeTruthy();
    });

    it('should not render last read chapter when not provided', () => {
        const { queryByText } = renderWithTheme(<StoryCard {...defaultProps} />);

        expect(queryByText(/Last read:/)).toBeNull();
    });

    it('should render progress bar when progress is provided', () => {
        const { getByTestId } = renderWithTheme(
            <StoryCard {...defaultProps} progress={0.5} />
        );

        expect(getByTestId('story-card-progress')).toBeTruthy();
    });

    it('should not render progress bar when progress is not provided', () => {
        const { queryByTestId } = renderWithTheme(<StoryCard {...defaultProps} />);

        expect(queryByTestId('story-card-progress')).toBeNull();
    });

    it('should handle progress value of 0', () => {
        const { getByTestId } = renderWithTheme(
            <StoryCard {...defaultProps} progress={0} />
        );

        expect(getByTestId('story-card-progress')).toBeTruthy();
    });

    it('should handle progress value of 1', () => {
        const { getByTestId } = renderWithTheme(
            <StoryCard {...defaultProps} progress={1} />
        );

        expect(getByTestId('story-card-progress')).toBeTruthy();
    });

    it('should render long title with truncation', () => {
        const longTitle = 'This is a very long title that should be truncated because it exceeds the maximum line limit';
        const { getByText } = renderWithTheme(
            <StoryCard {...defaultProps} title={longTitle} />
        );

        expect(getByText(longTitle)).toBeTruthy();
    });

    it('should render card with correct styles', () => {
        const { getByTestId } = renderWithTheme(<StoryCard {...defaultProps} />);

        const card = getByTestId('story-card');
        expect(card).toBeTruthy();
    });

    it('should use theme colors for text', () => {
        const { getByText } = renderWithTheme(<StoryCard {...defaultProps} />);

        expect(getByText('Test Story')).toBeTruthy();
    });

    it('should handle all optional props together', () => {
        const props = {
            ...defaultProps,
            coverUrl: 'https://example.com/cover.jpg',
            sourceName: 'RoyalRoad',
            score: '4.8',
            progress: 0.75,
            lastReadChapterName: 'Chapter 42',
        };

        const { getByText, getByTestId } = renderWithTheme(<StoryCard {...props} />);

        expect(getByText('Test Story')).toBeTruthy();
        expect(getByText('Test Author')).toBeTruthy();
        expect(getByText('RoyalRoad')).toBeTruthy();
        expect(getByText('4.8')).toBeTruthy();
        expect(getByText(/Last read: Chapter 42/)).toBeTruthy();
        expect(getByTestId('story-card-cover')).toBeTruthy();
        expect(getByTestId('story-card-progress')).toBeTruthy();
    });
});
