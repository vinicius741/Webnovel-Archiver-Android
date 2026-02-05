import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { StoryActions } from '../details/StoryActions';
import { ThemeProvider } from 'react-native-paper';

jest.mock('react-native-paper', () => {
    const OriginalModule = jest.requireActual('react-native-paper');
    return {
        ...OriginalModule,
        useTheme: jest.fn().mockReturnValue(OriginalModule.MD3LightTheme),
    };
});

const mockTheme = {
    ...jest.requireActual('react-native-paper').MD3LightTheme,
};

describe('StoryActions', () => {
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
        downloading: false,
        syncing: false,
        generating: false,
        epubProgress: null,
        syncStatus: undefined,
        downloadProgress: 0.5,
        downloadStatus: 'Downloading chapter 50/100',
        onSync: jest.fn(),
        onGenerateOrRead: jest.fn(),
    };

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <ThemeProvider theme={mockTheme as any}>
                {component}
            </ThemeProvider>
        );
    };

    it('should render Sync Chapters button', () => {
        const { getByTestId } = renderWithTheme(<StoryActions {...defaultProps} />);

        expect(getByTestId('sync-button')).toBeTruthy();
    });

    it('should render Syncing... button when syncing', () => {
        const { getByTestId } = renderWithTheme(
            <StoryActions {...defaultProps} syncing={true} />
        );

        expect(getByTestId('sync-button')).toBeTruthy();
    });

    it('should call onSync when Sync Chapters button is pressed', () => {
        const { getByTestId } = renderWithTheme(<StoryActions {...defaultProps} />);

        fireEvent.press(getByTestId('sync-button'));
        expect(defaultProps.onSync).toHaveBeenCalledTimes(1);
    });

    it('should render progress bar when downloading', () => {
        const { getAllByTestId } = renderWithTheme(
            <StoryActions {...defaultProps} downloading={true} />
        );

        expect(getAllByTestId('progress-bar').length).toBeGreaterThan(0);
    });

    it('should render download status text when downloading', () => {
        const { getByText } = renderWithTheme(
            <StoryActions {...defaultProps} downloading={true} />
        );

        expect(getByText('Downloading chapter 50/100')).toBeTruthy();
    });

    it('should render sync status when syncing', () => {
        const props = {
            ...defaultProps,
            syncStatus: 'Checking for updates...',
        };
        const { getByText } = renderWithTheme(<StoryActions {...props} syncing={true} />);

        expect(getByText('Checking for updates...')).toBeTruthy();
    });

    it('should render Read EPUB button when no epub path', () => {
        const { getByTestId } = renderWithTheme(<StoryActions {...defaultProps} />);

        expect(getByTestId('generate-button')).toBeTruthy();
    });

    it('should render Read EPUB button when epub path exists', () => {
        const props = {
            ...defaultProps,
            story: { ...defaultProps.story, epubPath: '/path/to/epub.epub' },
        };
        const { getByTestId } = renderWithTheme(<StoryActions {...props} />);

        expect(getByTestId('generate-button')).toBeTruthy();
    });

    it('should call onGenerateOrRead when Read EPUB button is pressed', () => {
        const { getByTestId } = renderWithTheme(<StoryActions {...defaultProps} />);

        fireEvent.press(getByTestId('generate-button'));
        expect(defaultProps.onGenerateOrRead).toHaveBeenCalledTimes(1);
    });

    it('should render generating progress when generating', () => {
        const props = {
            ...defaultProps,
            generating: true,
            epubProgress: {
                current: 50,
                total: 100,
                percentage: 50,
                stage: 'Generating',
                status: 'Generating chapters...',
            },
        };
        const { getAllByTestId } = renderWithTheme(<StoryActions {...props} />);

        expect(getAllByTestId('progress-bar').length).toBeGreaterThan(0);
    });

    it('should render generating status text when generating', () => {
        const props = {
            ...defaultProps,
            generating: true,
            epubProgress: {
                current: 50,
                total: 100,
                percentage: 50,
                stage: 'Generating',
                status: 'Generating chapters...',
            },
        };
        const { getByText } = renderWithTheme(<StoryActions {...props} />);

        expect(getByText('Generating chapters...')).toBeTruthy();
    });

    it('should render stale indicator when epub is out of date', () => {
        const props = {
            ...defaultProps,
            story: { ...defaultProps.story, epubStale: true, epubPath: '/path/to/epub.epub' },
        };
        const { getByText } = renderWithTheme(<StoryActions {...props} />);

        expect(getByText('EPUB out of date')).toBeTruthy();
    });

    it('should render both action buttons', () => {
        const { getByTestId } = renderWithTheme(<StoryActions {...defaultProps} />);

        expect(getByTestId('sync-button')).toBeTruthy();
        expect(getByTestId('generate-button')).toBeTruthy();
    });
});
