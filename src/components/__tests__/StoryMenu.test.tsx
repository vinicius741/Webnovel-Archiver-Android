import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { StoryMenu } from '../details/StoryMenu';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('react-native-paper', () => {
    const React = require('react');
    const { View, Text } = jest.requireActual('react-native');
    const OriginalModule = jest.requireActual('react-native-paper');
    const mockTheme = OriginalModule.MD3LightTheme;

    const MockIconButton = ({ onPress, testID, icon }: any) => {
        return React.createElement(
            View,
            {
                testID,
                accessible: true,
                accessibilityRole: 'button',
                accessibilityLabel: icon,
                onPress: onPress,
            },
            React.createElement(Text, null, icon)
        );
    };

    const MockMenu = ({ visible, children, anchor }: any) => {
        return React.createElement(
            View,
            { testID: 'menu-container' },
            anchor,
            visible && React.createElement(View, { testID: 'menu-content' }, children)
        );
    };

    (MockMenu as any).Item = ({ title, onPress, disabled }: any) => {
        return React.createElement(
            View,
            {
                testID: `menu-item-${title.toLowerCase().replace(/\s+/g, '-')}`,
                onPress: disabled ? undefined : onPress,
            },
            React.createElement(Text, null, title)
        );
    };

    return {
        ...OriginalModule,
        Menu: MockMenu,
        useTheme: jest.fn().mockReturnValue(mockTheme),
        IconButton: MockIconButton,
        Divider: () => React.createElement(View, { testID: 'divider' }),
    };
});

jest.mock('react-native-safe-area-context', () => ({
    ...jest.requireActual('react-native-safe-area-context'),
    useSafeAreaInsets: jest.fn().mockReturnValue({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

describe('StoryMenu', () => {
    const defaultProps = {
        onDownloadRange: jest.fn(),
        onApplySentenceRemoval: jest.fn(),
        onDelete: jest.fn(),
        disabled: false,
    };

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <SafeAreaProvider>
                {component}
            </SafeAreaProvider>
        );
    };

    it('should render without crashing', () => {
        render(<StoryMenu {...defaultProps} />);
        expect(true).toBe(true);
    });

    it('should render menu container', () => {
        const { getByTestId } = render(<StoryMenu {...defaultProps} />);
        expect(getByTestId('menu-container')).toBeTruthy();
    });

    it('should render menu with correct testID', () => {
        const { getByTestId } = render(<StoryMenu {...defaultProps} />);
        expect(getByTestId('menu-container')).toBeTruthy();
    });
});
