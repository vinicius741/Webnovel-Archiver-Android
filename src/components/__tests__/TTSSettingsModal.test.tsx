import React from 'react';
import { render, fireEvent, waitFor, act } from '@testing-library/react-native';
import { View } from 'react-native';
import { TTSSettingsModal } from '../TTSSettingsModal';
import { ThemeProvider } from 'react-native-paper';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import Slider from '@react-native-community/slider';
import * as Speech from 'expo-speech';

jest.mock('expo-speech', () => ({
    getAvailableVoicesAsync: jest.fn().mockResolvedValue([
        {
            identifier: 'en-us-voice1',
            name: 'English US Voice 1',
            language: 'en-US',
            quality: 'Default',
        },
        {
            identifier: 'en-us-voice2',
            name: 'English US Voice 2',
            language: 'en-US',
            quality: 'Enhanced',
        },
    ]),
}));

jest.mock('react-native-paper', () => {
    const React = require('react');
    const { Text, View, ScrollView } = jest.requireActual('react-native');

    const mockTheme = {
        colors: {
            primary: '#6200ee',
            onPrimary: '#ffffff',
            surface: '#ffffff',
            surfaceVariant: '#f0f0f0',
        },
        animation: {
            scale: 1.0,
        },
    };

    const MockThemeProvider = ({ children, theme }: any) => {
        return React.createElement(React.Fragment, null, children);
    };

    const MockModal = ({ visible, children, onDismiss, contentContainerStyle, testID }: any) => {
        if (!visible) return null;
        return React.createElement(
            View,
            { testID, style: contentContainerStyle },
            children
        );
    };

    const MockSlider = ({ testID, onSlidingComplete, onValueChange, ...props }: any) => {
        return React.createElement(
            View,
            { testID, onSlidingComplete, onValueChange, ...props },
            React.createElement(Text, null, 'Slider mock')
        );
    };

    const MockList = ({ children }: any) => {
        return React.createElement(View, null, children);
    };

    const MockListItem = ({ title, onPress, description, testID }: any) => {
        return React.createElement(
            View,
            {
                testID: testID || `voice-item-${title?.replace(/\s/g, '-')}`,
                accessible: true,
                accessibilityRole: 'button',
                accessibilityLabel: title,
                onPress,
            },
            React.createElement(Text, null, title)
        );
    };

    const MockSearchbar = ({ placeholder, onChangeText, value, testID }: any) => {
        return React.createElement(
            View,
            { testID: testID || 'searchbar' },
            React.createElement(Text, null, placeholder || value)
        );
    };

    const MockButton = ({ children, onPress, mode, style }: any) => {
        return React.createElement(
            View,
            {
                accessible: true,
                accessibilityRole: 'button',
                onPress,
                style,
            },
            React.createElement(Text, null, children)
        );
    };

    const MockDivider = ({ style }: any) => {
        return React.createElement(View, { style });
    };

    const MockPortal = ({ children }: any) => {
        return React.createElement(React.Fragment, null, children);
    };

    return {
        useTheme: jest.fn().mockReturnValue(mockTheme),
        ThemeProvider: MockThemeProvider,
        Modal: MockModal,
        Slider: MockSlider,
        List: { Item: MockListItem },
        Searchbar: MockSearchbar,
        Button: MockButton,
        Divider: MockDivider,
        Portal: MockPortal,
        Text: Text,
        ScrollView: ScrollView,
    };
});

jest.mock('react-native-safe-area-context', () => {
    const React = require('react');
    const { View } = jest.requireActual('react-native');

    const MockSafeAreaProvider = ({ children }: any) => {
        return React.createElement(View, null, children);
    };

    return {
        SafeAreaProvider: MockSafeAreaProvider,
        useSafeAreaInsets: jest.fn().mockReturnValue({ top: 0, bottom: 0, left: 0, right: 0 }),
    };
});

jest.mock('@react-native-community/slider', () => {
    const React = require('react');
    const { Text } = jest.requireActual('react-native');

    const MockSliderComponent = ({ testID, onValueChange, ...props }: any) => {
        return React.createElement(
            'View',
            { testID },
            React.createElement(Text, null, 'Slider mock')
        );
    };

    return {
        __esModule: true,
        default: MockSliderComponent,
    };
});

describe('TTSSettingsModal', () => {
    const mockTheme = {
        colors: {
            primary: '#6200ee',
            onPrimary: '#ffffff',
            surface: '#ffffff',
            surfaceVariant: '#f0f0f0',
        },
        animation: {
            scale: 1.0,
        },
    };

    const defaultProps = {
        visible: true,
        onDismiss: jest.fn(),
        settings: {
            voiceIdentifier: 'en-us-voice1',
            pitch: 1.0,
            rate: 1.0,
            chunkSize: 100,
        },
        onSettingsChange: jest.fn(),
    };

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <SafeAreaProvider>
                {component}
            </SafeAreaProvider>
        );
    };

    it('should render modal when visible', () => {
        const { queryByTestId } = renderWithTheme(<TTSSettingsModal {...defaultProps} />);
        const modal = queryByTestId('modal');
        expect(modal).toBeTruthy();
    });

    it('should not render modal when not visible', () => {
        const { queryByTestId } = renderWithTheme(<TTSSettingsModal {...defaultProps} visible={false} />);
        const modal = queryByTestId('modal');
        expect(modal).toBeNull();
    });

    it('should render sliders', () => {
        const { queryByTestId } = renderWithTheme(<TTSSettingsModal {...defaultProps} />);
        expect(queryByTestId('pitch-slider')).toBeTruthy();
        expect(queryByTestId('rate-slider')).toBeTruthy();
        expect(queryByTestId('chunk-slider')).toBeTruthy();
    });

    it('should call onDismiss when Done button is pressed', () => {
        const { queryByText } = renderWithTheme(<TTSSettingsModal {...defaultProps} />);
        const doneButton = queryByText('Done');
        if (doneButton) {
            fireEvent.press(doneButton);
            expect(defaultProps.onDismiss).toHaveBeenCalledTimes(1);
        }
    });

    it('should call onSettingsChange when sliders change', () => {
        const { queryByTestId } = renderWithTheme(<TTSSettingsModal {...defaultProps} />);
        const slider = queryByTestId('pitch-slider');
        if (slider) {
            fireEvent(slider, 'onSlidingComplete', 1.5);
            expect(defaultProps.onSettingsChange).toHaveBeenCalled();
        }
    });
});
