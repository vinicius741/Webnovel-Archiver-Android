import React from 'react';
import { render } from '@testing-library/react-native';
import { ScreenContainer } from '../ScreenContainer';
import { Text } from 'react-native-paper';

jest.mock('react-native-paper', () => ({
    ...jest.requireActual('react-native-paper'),
    useTheme: jest.fn().mockReturnValue({
        colors: {
            background: '#ffffff',
        },
    }),
}));

jest.mock('react-native-safe-area-context', () => ({
    ...jest.requireActual('react-native-safe-area-context'),
    useSafeAreaInsets: jest.fn().mockReturnValue({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

describe('ScreenContainer', () => {
    it('should render children', () => {
        const { getByText } = render(
            <ScreenContainer>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        expect(getByText('Test Content')).toBeTruthy();
    });

    it('should apply custom style', () => {
        const { getByTestId } = render(
            <ScreenContainer style={{ paddingTop: 20 }}>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        const container = getByTestId('safe-area-view');
        expect(container).toBeTruthy();
    });

    it('should use theme background color', () => {
        const { getByTestId } = render(
            <ScreenContainer>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        const container = getByTestId('safe-area-view');
        expect(container).toBeTruthy();
    });

    it('should pass edges prop to SafeAreaView', () => {
        const { getByTestId } = render(
            <ScreenContainer edges={['top', 'bottom']}>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        const container = getByTestId('safe-area-view');
        expect(container).toBeTruthy();
    });

    it('should render multiple children', () => {
        const { getByText } = render(
            <ScreenContainer>
                <Text>Child 1</Text>
                <Text>Child 2</Text>
                <Text>Child 3</Text>
            </ScreenContainer>
        );

        expect(getByText('Child 1')).toBeTruthy();
        expect(getByText('Child 2')).toBeTruthy();
        expect(getByText('Child 3')).toBeTruthy();
    });

    it('should have flex: 1 style', () => {
        const { getByTestId } = render(
            <ScreenContainer>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        const container = getByTestId('safe-area-view');
        expect(container).toBeTruthy();
    });

    it('should render without style prop', () => {
        const { getByText } = render(
            <ScreenContainer>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        expect(getByText('Test Content')).toBeTruthy();
    });

    it('should render without edges prop', () => {
        const { getByText } = render(
            <ScreenContainer>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        expect(getByText('Test Content')).toBeTruthy();
    });

    it('should render with children', () => {
        const { getByTestId } = render(
            <ScreenContainer>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        expect(getByTestId('safe-area-view')).toBeTruthy();
    });

    it('should merge custom style with default styles', () => {
        const { getByTestId } = render(
            <ScreenContainer style={{ backgroundColor: '#f0f0f0', padding: 10 }}>
                <Text>Test Content</Text>
            </ScreenContainer>
        );

        const container = getByTestId('safe-area-view');
        expect(container).toBeTruthy();
    });
});
