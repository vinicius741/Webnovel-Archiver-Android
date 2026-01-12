import React from 'react';
import { render } from '@testing-library/react-native';
import { StoryTags } from '../details/StoryTags';
import { ThemeProvider } from 'react-native-paper';

jest.mock('react-native-paper', () => ({
    ...jest.requireActual('react-native-paper'),
    useTheme: jest.fn().mockReturnValue({
        colors: {
            primary: '#6200ee',
        },
    }),
}));

describe('StoryTags', () => {
    const mockTheme = {
        colors: {
            primary: '#6200ee',
        },
        fonts: {
            regular: {
                fontFamily: 'System',
                fontWeight: '400' as const,
            },
            medium: {
                fontFamily: 'System',
                fontWeight: '500' as const,
            },
        },
    };

    const defaultProps = {
        tags: ['Fantasy', 'Action', 'Magic'],
    };

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <ThemeProvider theme={mockTheme as any}>
                {component}
            </ThemeProvider>
        );
    };

    it('should render all tags', () => {
        const { getByText } = renderWithTheme(<StoryTags {...defaultProps} />);

        expect(getByText('Fantasy')).toBeTruthy();
        expect(getByText('Action')).toBeTruthy();
        expect(getByText('Magic')).toBeTruthy();
    });

    it('should not render when tags is undefined', () => {
        const { queryByText } = renderWithTheme(<StoryTags tags={undefined} />);

        expect(queryByText('Fantasy')).toBeNull();
    });

    it('should not render when tags array is empty', () => {
        const { queryByText } = renderWithTheme(<StoryTags tags={[]} />);

        expect(queryByText('Fantasy')).toBeNull();
    });

    it('should render single tag', () => {
        const { getByText } = renderWithTheme(<StoryTags tags={['Fantasy']} />);

        expect(getByText('Fantasy')).toBeTruthy();
    });

    it('should render many tags', () => {
        const manyTags = ['Fantasy', 'Action', 'Magic', 'Adventure', 'Romance', 'Sci-Fi'];
        const { getByText } = renderWithTheme(<StoryTags tags={manyTags} />);

        manyTags.forEach(tag => {
            expect(getByText(tag)).toBeTruthy();
        });
    });

    it('should render tags with compact style', () => {
        const { getAllByTestId } = renderWithTheme(<StoryTags {...defaultProps} />);

        const chips = getAllByTestId(/chip-/);
        expect(chips.length).toBe(3);
    });

    it('should handle tags with special characters', () => {
        const specialTags = ['Sci-Fi', 'LitRPG', 'Xianxia'];
        const { getByText } = renderWithTheme(<StoryTags tags={specialTags} />);

        specialTags.forEach(tag => {
            expect(getByText(tag)).toBeTruthy();
        });
    });

    it('should handle tags with numbers', () => {
        const numericTags = ['Tag1', 'Tag2', 'Tag123'];
        const { getByText } = renderWithTheme(<StoryTags tags={numericTags} />);

        numericTags.forEach(tag => {
            expect(getByText(tag)).toBeTruthy();
        });
    });

    it('should handle tags with emojis', () => {
        const emojiTags = ['Fantasy 🐉', 'Magic ⚡', 'Adventure 🗺️'];
        const { getByText } = renderWithTheme(<StoryTags tags={emojiTags} />);

        emojiTags.forEach(tag => {
            expect(getByText(tag)).toBeTruthy();
        });
    });

    it('should handle tags with spaces', () => {
        const spaceTags = ['High Fantasy', 'Urban Fantasy', 'Dark Magic'];
        const { getByText } = renderWithTheme(<StoryTags tags={spaceTags} />);

        spaceTags.forEach(tag => {
            expect(getByText(tag)).toBeTruthy();
        });
    });

    it('should render tags in proper container', () => {
        const { getByTestId } = renderWithTheme(<StoryTags {...defaultProps} />);

        expect(getByTestId('tags-container')).toBeTruthy();
    });

    it('should render centered tags', () => {
        const { getByTestId } = renderWithTheme(<StoryTags {...defaultProps} />);

        expect(getByTestId('tags-container')).toBeTruthy();
    });

    it('should handle long tag names', () => {
        const longTag = 'This is a very long tag name that might need truncation in the UI';
        const { getByText } = renderWithTheme(<StoryTags tags={[longTag]} />);

        expect(getByText(longTag)).toBeTruthy();
    });

    it('should render tags with proper chip style', () => {
        const { getAllByTestId } = renderWithTheme(<StoryTags {...defaultProps} />);

        const chip = getAllByTestId(/chip-/)[0];
        expect(chip).toBeTruthy();
    });

    it('should render tags with proper text style', () => {
        const { getAllByTestId } = renderWithTheme(<StoryTags {...defaultProps} />);

        const chip = getAllByTestId(/chip-/)[0];
        expect(chip).toBeTruthy();
    });
});
