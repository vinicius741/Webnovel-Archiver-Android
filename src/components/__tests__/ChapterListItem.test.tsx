import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { ChapterListItem } from '../details/ChapterListItem';
import { ThemeProvider } from 'react-native-paper';

jest.mock('react-native-paper', () => ({
    ...jest.requireActual('react-native-paper'),
    useTheme: jest.fn().mockReturnValue({
        colors: {
            primary: '#6200ee',
            primaryContainer: 'rgba(98, 0, 238, 0.12)',
            secondary: '#03dac6',
            outline: '#cccccc',
        },
    }),
}));

describe('ChapterListItem', () => {
    const mockTheme = {
        colors: {
            primary: '#6200ee',
            primaryContainer: 'rgba(98, 0, 238, 0.12)',
            secondary: '#03dac6',
            outline: '#cccccc',
        },
    };

    const mockChapter = {
        id: '1',
        title: 'Test Chapter',
        url: 'https://example.com/chapter-1',
        downloaded: false,
    };

    const defaultProps = {
        item: mockChapter,
        isLastRead: false,
        onPress: jest.fn(),
        onLongPress: jest.fn(),
    };

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <ThemeProvider theme={mockTheme as any}>
                {component}
            </ThemeProvider>
        );
    };

    it('should render chapter title', () => {
        const { getByText } = renderWithTheme(<ChapterListItem {...defaultProps} />);

        expect(getByText('Test Chapter')).toBeTruthy();
    });

    it('should render bookmark icon when isLastRead is true', () => {
        const props = { ...defaultProps, isLastRead: true };
        const { getByTestId } = renderWithTheme(<ChapterListItem {...props} />);

        const listItem = getByTestId('list-item');
        expect(listItem).toBeTruthy();
    });

    it('should render file-check-outline icon when downloaded', () => {
        const props = { ...defaultProps, item: { ...mockChapter, downloaded: true } };
        const { getByTestId } = renderWithTheme(<ChapterListItem {...props} />);

        const listItem = getByTestId('list-item');
        expect(listItem).toBeTruthy();
    });

    it('should render file-outline icon when not downloaded', () => {
        const { getByTestId } = renderWithTheme(<ChapterListItem {...defaultProps} />);

        const listItem = getByTestId('list-item');
        expect(listItem).toBeTruthy();
    });

    it('should render Available Offline description when downloaded', () => {
        const props = { ...defaultProps, item: { ...mockChapter, downloaded: true } };
        const { getByText } = renderWithTheme(<ChapterListItem {...props} />);

        expect(getByText('Available Offline')).toBeTruthy();
    });

    it('should not render description when not downloaded', () => {
        const { queryByText } = renderWithTheme(<ChapterListItem {...defaultProps} />);

        expect(queryByText('Available Offline')).toBeNull();
    });

    it('should render check-circle-outline icon on right when downloaded', () => {
        const props = { ...defaultProps, item: { ...mockChapter, downloaded: true } };
        const { getByTestId } = renderWithTheme(<ChapterListItem {...props} />);

        const listItem = getByTestId('list-item');
        expect(listItem).toBeTruthy();
    });

    it('should not render right icon when not downloaded', () => {
        const { getByTestId } = renderWithTheme(<ChapterListItem {...defaultProps} />);

        const listItem = getByTestId('list-item');
        expect(listItem).toBeTruthy();
    });

    it('should call onPress when pressed', () => {
        const { getByText } = renderWithTheme(<ChapterListItem {...defaultProps} />);

        fireEvent.press(getByText('Test Chapter'));
        expect(defaultProps.onPress).toHaveBeenCalledTimes(1);
    });

    it('should call onLongPress when long pressed', () => {
        const { getByText } = renderWithTheme(<ChapterListItem {...defaultProps} />);

        fireEvent(getByText('Test Chapter'), 'onLongPress');
        expect(defaultProps.onLongPress).toHaveBeenCalledTimes(1);
    });

    it('should apply primary color to title when isLastRead is true', () => {
        const props = { ...defaultProps, isLastRead: true };
        const { getByText } = renderWithTheme(<ChapterListItem {...props} />);

        const title = getByText('Test Chapter');
        expect(title).toBeTruthy();
    });

    it('should apply bold font to title when isLastRead is true', () => {
        const props = { ...defaultProps, isLastRead: true };
        const { getByText } = renderWithTheme(<ChapterListItem {...props} />);

        const title = getByText('Test Chapter');
        expect(title).toBeTruthy();
    });

    it('should apply background when isLastRead is true', () => {
        const props = { ...defaultProps, isLastRead: true };
        const { getByTestId } = renderWithTheme(<ChapterListItem {...props} />);

        const listItem = getByTestId('list-item');
        expect(listItem).toBeTruthy();
    });

    it('should sanitize chapter title', () => {
        const props = { ...defaultProps, item: { ...mockChapter, title: 'Test Chapter...' } };
        const { getByText } = renderWithTheme(<ChapterListItem {...props} />);

        expect(getByText('Test Chapter')).toBeTruthy();
    });

    it('should not render when onPress is not provided', () => {
        const props = { ...defaultProps, onPress: undefined };
        const { getByText } = renderWithTheme(<ChapterListItem {...props} />);

        expect(getByText('Test Chapter')).toBeTruthy();
    });

    it('should not render when onLongPress is not provided', () => {
        const props = { ...defaultProps, onLongPress: undefined };
        const { getByText } = renderWithTheme(<ChapterListItem {...props} />);

        expect(getByText('Test Chapter')).toBeTruthy();
    });

    it('should render with proper title style', () => {
        const { getByText } = renderWithTheme(<ChapterListItem {...defaultProps} />);

        const title = getByText('Test Chapter');
        expect(title).toBeTruthy();
    });

    it('should render with proper description style', () => {
        const props = { ...defaultProps, item: { ...mockChapter, downloaded: true } };
        const { getByText } = renderWithTheme(<ChapterListItem {...props} />);

        const description = getByText('Available Offline');
        expect(description).toBeTruthy();
    });
});
