import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { DownloadRangeDialog } from '../details/DownloadRangeDialog';
import { MD3LightTheme, PaperProvider } from 'react-native-paper';

describe('DownloadRangeDialog', () => {
    const mockTheme = {
        ...MD3LightTheme,
        colors: {
            ...MD3LightTheme.colors,
            primary: '#6200ee',
        },
    };

    const defaultProps = {
        visible: true,
        onDismiss: jest.fn(),
        onDownload: jest.fn(),
        totalChapters: 100,
    };

    const renderWithTheme = (component: React.ReactElement) => {
        return render(
            <PaperProvider theme={mockTheme}>
                {component}
            </PaperProvider>
        );
    };

    beforeEach(() => {
        defaultProps.onDismiss.mockClear();
        defaultProps.onDownload.mockClear();
    });

    it('should render dialog when visible', () => {
        const { getByText } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        expect(getByText('Download Range')).toBeTruthy();
    });

    it('should not render when not visible', () => {
        const { queryByText } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} visible={false} />
        );

        expect(queryByText('Download Range')).toBeNull();
    });

    it('should display total chapters', () => {
        const { getByText } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        expect(getByText('Total Chapters: 100')).toBeTruthy();
    });

    it('should initialize start input to 1', () => {
        const { getByDisplayValue } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        expect(getByDisplayValue('1')).toBeTruthy();
    });

    it('should initialize end input to total chapters', () => {
        const { getByDisplayValue } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        expect(getByDisplayValue('100')).toBeTruthy();
    });

    it('should render From label', () => {
        const { getAllByText } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        const fromElements = getAllByText('From');
        expect(fromElements.length).toBeGreaterThan(0);
    });

    it('should render To label', () => {
        const { getAllByText } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        const toElements = getAllByText('To');
        expect(toElements.length).toBeGreaterThan(0);
    });

    it('should render Cancel button', () => {
        const { getByText } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        expect(getByText('Cancel')).toBeTruthy();
    });

    it('should render Download button', () => {
        const { getByText } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        expect(getByText('Download')).toBeTruthy();
    });

    it('should call onDismiss when Cancel button is pressed', () => {
        const { getByText } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        fireEvent.press(getByText('Cancel'));
        expect(defaultProps.onDismiss).toHaveBeenCalledTimes(1);
    });

    it('should show error for non-numeric input', () => {
        const { getByText, getByDisplayValue, queryByText } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} />
        );

        const input = getByDisplayValue('1');
        fireEvent.changeText(input, 'abc');
        
        fireEvent.press(getByText('Download'));
        expect(queryByText('Please enter valid numbers')).toBeTruthy();
    });

    it('should show error for start chapter below 1', () => {
        const { getByText, getByDisplayValue, queryByText } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} />
        );

        const input = getByDisplayValue('1');
        fireEvent.changeText(input, '0');
        
        fireEvent.press(getByText('Download'));
        expect(queryByText('Range must be between 1 and 100')).toBeTruthy();
    });

    it('should show error for end chapter above total', () => {
        const { getByText, getByDisplayValue, queryByText } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} />
        );

        const input = getByDisplayValue('100');
        fireEvent.changeText(input, '101');
        
        fireEvent.press(getByText('Download'));
        expect(queryByText('Range must be between 1 and 100')).toBeTruthy();
    });

    it('should show error when start is greater than end', () => {
        const { getByText, getByDisplayValue, queryByText } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} />
        );

        const startInput = getByDisplayValue('1');
        const endInput = getByDisplayValue('100');
        fireEvent.changeText(startInput, '50');
        fireEvent.changeText(endInput, '10');
        
        fireEvent.press(getByText('Download'));
        expect(queryByText('Start chapter cannot be greater than end chapter')).toBeTruthy();
    });

    it('should call onDownload with correct values when valid', () => {
        const { getByText } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        fireEvent.press(getByText('Download'));
        expect(defaultProps.onDownload).toHaveBeenCalledWith(1, 100);
    });

    it('should call onDismiss after successful download', () => {
        const mockOnDownload = jest.fn();
        const mockOnDismiss = jest.fn();
        const { getByText } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} onDownload={mockOnDownload} onDismiss={mockOnDismiss} />
        );

        fireEvent.press(getByText('Download'));
        expect(mockOnDownload).toHaveBeenCalledWith(1, 100);
        expect(mockOnDismiss).toHaveBeenCalledTimes(1);
    });

    it('should not call onDownload when input is invalid', () => {
        const mockOnDownload = jest.fn();
        const mockOnDismiss = jest.fn();
        const { getByText, getByDisplayValue } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} onDownload={mockOnDownload} onDismiss={mockOnDismiss} />
        );

        const input = getByDisplayValue('1');
        fireEvent.changeText(input, 'abc');

        fireEvent.press(getByText('Download'));
        expect(mockOnDownload).not.toHaveBeenCalled();
        expect(mockOnDismiss).not.toHaveBeenCalled();
    });

    it('should reset inputs when dialog becomes visible', () => {
        const { rerender, getByDisplayValue } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} />
        );

        expect(getByDisplayValue('1')).toBeTruthy();
        expect(getByDisplayValue('100')).toBeTruthy();

        rerender(
            <PaperProvider theme={mockTheme}>
                <DownloadRangeDialog {...defaultProps} totalChapters={200} />
            </PaperProvider>
        );

        expect(getByDisplayValue('1')).toBeTruthy();
        expect(getByDisplayValue('200')).toBeTruthy();
    });

    it('should clear error when dialog becomes visible', () => {
        const { getByText, getByDisplayValue, queryByText, rerender } = renderWithTheme(
            <DownloadRangeDialog {...defaultProps} />
        );

        const input = getByDisplayValue('1');
        fireEvent.changeText(input, 'abc');

        fireEvent.press(getByText('Download'));
        expect(queryByText('Please enter valid numbers')).toBeTruthy();

        rerender(
            <PaperProvider theme={mockTheme}>
                <DownloadRangeDialog {...defaultProps} visible={false} />
            </PaperProvider>
        );
    });

    it('should handle valid range in middle of chapters', () => {
        const { getByText, getByDisplayValue } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        const startInput = getByDisplayValue('1');
        const endInput = getByDisplayValue('100');
        fireEvent.changeText(startInput, '20');
        fireEvent.changeText(endInput, '30');
        
        fireEvent.press(getByText('Download'));
        expect(defaultProps.onDownload).toHaveBeenCalledWith(20, 30);
    });

    it('should handle range of single chapter', () => {
        const { getByText, getByDisplayValue } = renderWithTheme(<DownloadRangeDialog {...defaultProps} />);

        const startInput = getByDisplayValue('1');
        const endInput = getByDisplayValue('100');
        fireEvent.changeText(startInput, '50');
        fireEvent.changeText(endInput, '50');
        
        fireEvent.press(getByText('Download'));
        expect(defaultProps.onDownload).toHaveBeenCalledWith(50, 50);
    });
});
