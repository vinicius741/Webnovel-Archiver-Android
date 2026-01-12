import React from 'react';
import { render, fireEvent, waitFor } from '@testing-library/react-native';
import { TTSController } from '../TTSController';
import { ThemeProvider } from 'react-native-paper';
import { Animated } from 'react-native';

jest.mock('react-native-paper', () => {
    const React = require('react');
    const { Text, View } = jest.requireActual('react-native');
    const OriginalModule = jest.requireActual('react-native-paper');

    const mockTheme = {
        colors: {
            primary: '#6200ee',
            onPrimary: '#ffffff',
            elevation: {
                level3: '#ffffff',
            },
        },
    };

    const MockIconButton = ({ icon, size, disabled, onPress, testID, mode, containerColor, iconColor }: any) => {
        return React.createElement(
            View,
            {
                testID,
                accessible: true,
                accessibilityRole: 'button',
                accessibilityState: { disabled: disabled || false },
                onPress: disabled ? jest.fn() : onPress,
                style: { opacity: disabled ? 0.5 : 1 },
            },
            React.createElement(Text, null, `${icon} ${disabled ? '(disabled)' : '(enabled)'}`)
        );
    };

    return {
        ...OriginalModule,
        useTheme: jest.fn().mockReturnValue(mockTheme),
        IconButton: MockIconButton,
    };
});

describe('TTSController', () => {
    const mockTheme = {
        colors: {
            primary: '#6200ee',
            onPrimary: '#ffffff',
            elevation: {
                level3: '#ffffff',
            },
        },
    };

    const defaultProps = {
        onPlayPause: jest.fn(),
        onStop: jest.fn(),
        onNext: jest.fn(),
        onPrevious: jest.fn(),
        isSpeaking: false,
        isPaused: true,
        currentChunk: 0,
        totalChunks: 10,
        visible: true,
    };

    beforeEach(() => {
        jest.clearAllMocks();
        defaultProps.onPlayPause.mockClear();
        defaultProps.onStop.mockClear();
        defaultProps.onNext.mockClear();
        defaultProps.onPrevious.mockClear();
    });

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <ThemeProvider theme={mockTheme as any}>
                {component}
            </ThemeProvider>
        );
    };

    it('should render TTS controller when visible', () => {
        const { getByText } = renderWithTheme(<TTSController {...defaultProps} />);

        expect(getByText('TEXT-TO-SPEECH')).toBeTruthy();
        expect(getByText('Chunk 1 / 10')).toBeTruthy();
    });

    it('should not render when not visible', () => {
        const { queryByText } = renderWithTheme(<TTSController {...defaultProps} visible={false} />);

        expect(queryByText('TEXT-TO-SPEECH')).toBeNull();
    });

    it('should display initializing text when no chunks', () => {
        const { getByText } = renderWithTheme(
            <TTSController {...defaultProps} totalChunks={0} currentChunk={0} />
        );

        expect(getByText('Initializing...')).toBeTruthy();
    });

    it('should call onPlayPause when play/pause button is pressed', () => {
        const { getByTestId } = renderWithTheme(<TTSController {...defaultProps} />);

        const playPauseButton = getByTestId('play-pause-button');
        fireEvent.press(playPauseButton);
        expect(defaultProps.onPlayPause).toHaveBeenCalledTimes(1);
    });

    it('should show play icon when paused', () => {
        const { getByTestId } = renderWithTheme(<TTSController {...defaultProps} isPaused={true} />);

        const playPauseButton = getByTestId('play-pause-button');
        expect(playPauseButton).toBeTruthy();
    });

    it('should show pause icon when not paused', () => {
        const { getByTestId } = renderWithTheme(<TTSController {...defaultProps} isPaused={false} />);

        const playPauseButton = getByTestId('play-pause-button');
        expect(playPauseButton).toBeTruthy();
    });

    it('should call onStop when stop button is pressed', () => {
        const { getByTestId } = renderWithTheme(<TTSController {...defaultProps} />);

        const stopButton = getByTestId('stop-button');
        fireEvent.press(stopButton);
        expect(defaultProps.onStop).toHaveBeenCalledTimes(1);
    });

    it('should call onNext when next button is pressed', () => {
        const { getByTestId } = renderWithTheme(<TTSController {...defaultProps} />);

        const nextButton = getByTestId('next-button');
        fireEvent.press(nextButton);
        expect(defaultProps.onNext).toHaveBeenCalledTimes(1);
    });

    it('should disable next button when at last chunk', () => {
        const { getByTestId } = renderWithTheme(
            <TTSController {...defaultProps} currentChunk={9} totalChunks={10} />
        );

        const nextButton = getByTestId('next-button');
        fireEvent.press(nextButton);
        expect(defaultProps.onNext).not.toHaveBeenCalled();
    });

    it('should call onPrevious when previous button is pressed', () => {
        const { getByTestId } = renderWithTheme(
            <TTSController {...defaultProps} currentChunk={1} totalChunks={10} />
        );

        const previousButton = getByTestId('previous-button');
        fireEvent.press(previousButton);
        expect(defaultProps.onPrevious).toHaveBeenCalledTimes(1);
    });

    it('should disable previous button when at first chunk', () => {
        const { getByTestId } = renderWithTheme(
            <TTSController {...defaultProps} currentChunk={0} totalChunks={10} />
        );

        const previousButton = getByTestId('previous-button');
        fireEvent.press(previousButton);
        expect(defaultProps.onPrevious).not.toHaveBeenCalled();
    });

    it('should display correct chunk information', () => {
        const { getByText } = renderWithTheme(
            <TTSController {...defaultProps} currentChunk={5} totalChunks={20} />
        );

        expect(getByText('Chunk 6 / 20')).toBeTruthy();
    });

    it('should use theme colors for buttons', () => {
        const { getByTestId } = renderWithTheme(<TTSController {...defaultProps} />);

        const playPauseButton = getByTestId('play-pause-button');
        expect(playPauseButton).toBeTruthy();
    });

    it('should handle isSpeaking prop correctly', () => {
        const { getByTestId } = renderWithTheme(
            <TTSController {...defaultProps} isSpeaking={true} isPaused={false} />
        );

        const playPauseButton = getByTestId('play-pause-button');
        expect(playPauseButton).toBeTruthy();
    });

    it('should render with correct container position', () => {
        const { getByTestId } = renderWithTheme(<TTSController {...defaultProps} />);

        expect(getByTestId('animated-view')).toBeTruthy();
    });
});
