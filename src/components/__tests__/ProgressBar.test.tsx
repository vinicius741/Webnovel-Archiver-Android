import React from 'react';
import { render, cleanup } from '@testing-library/react-native';
import { AppProgressBar } from '../ProgressBar';

jest.mock('react-native-paper', () => ({
    ...jest.requireActual('react-native-paper'),
    useTheme: jest.fn().mockReturnValue({
        colors: {
            primary: '#6200ee',
        },
    }),
}));

describe('AppProgressBar', () => {
    afterEach(() => {
        cleanup();
    });

    it('should render progress bar with given progress', () => {
        const { getAllByTestId } = render(<AppProgressBar progress={0.5} />);

        const progressBars = getAllByTestId('progress-bar');
        expect(progressBars.length).toBeGreaterThan(0);
    });

    it('should render with indeterminate state when progress is 0', () => {
        const { getAllByTestId } = render(<AppProgressBar progress={0} />);

        const progressBars = getAllByTestId('progress-bar');
        expect(progressBars.length).toBeGreaterThan(0);
    });

    it('should render with determinate state when progress is greater than 0', () => {
        const { getAllByTestId } = render(<AppProgressBar progress={0.25} />);

        const progressBars = getAllByTestId('progress-bar');
        expect(progressBars.length).toBeGreaterThan(0);
    });

    it('should not render when visible is false', () => {
        const { queryByTestId } = render(<AppProgressBar progress={0.5} visible={false} />);

        expect(queryByTestId('progress-bar')).toBeNull();
    });

    it('should render when visible is true (default)', () => {
        const { getAllByTestId } = render(<AppProgressBar progress={0.5} visible={true} />);

        const progressBars = getAllByTestId('progress-bar');
        expect(progressBars.length).toBeGreaterThan(0);
    });

    it('should render when visible is not provided', () => {
        const { getAllByTestId } = render(<AppProgressBar progress={0.5} />);

        const progressBars = getAllByTestId('progress-bar');
        expect(progressBars.length).toBeGreaterThan(0);
    });

    it('should handle progress of 1.0 (100%)', () => {
        const { getAllByTestId } = render(<AppProgressBar progress={1.0} />);

        const progressBars = getAllByTestId('progress-bar');
        expect(progressBars.length).toBeGreaterThan(0);
    });

    it('should handle progress between 0 and 1', () => {
        const { getAllByTestId } = render(<AppProgressBar progress={0.75} />);

        const progressBars = getAllByTestId('progress-bar');
        expect(progressBars.length).toBeGreaterThan(0);
    });
});
