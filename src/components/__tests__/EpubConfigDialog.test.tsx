import React from 'react';
import { render, fireEvent, waitFor } from '@testing-library/react-native';
import { MD3LightTheme, PaperProvider } from 'react-native-paper';

import { EpubConfigDialog } from '../details/EpubConfigDialog';

describe('EpubConfigDialog', () => {
    const baseProps = {
        visible: true,
        onDismiss: jest.fn(),
        onSave: jest.fn(),
        totalChapters: 200,
        downloadedChapterCount: 75,
        hasBookmark: true,
        initialConfig: {
            maxChaptersPerEpub: 150,
            rangeStart: 1,
            rangeEnd: 200,
            startAfterBookmark: false,
        },
    };

    const renderDialog = (props: Partial<typeof baseProps> = {}) => (
        render(
            <PaperProvider theme={MD3LightTheme}>
                <EpubConfigDialog {...baseProps} {...props} />
            </PaperProvider>
        )
    );

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('renders with initial values', () => {
        const { getByDisplayValue, getByText } = renderDialog();

        expect(getByDisplayValue('150')).toBeTruthy();
        expect(getByDisplayValue('1')).toBeTruthy();
        expect(getByDisplayValue('200')).toBeTruthy();
        expect(getByText('Downloaded chapters: 75. EPUB generation only includes downloaded chapters.')).toBeTruthy();
    });

    it('shows validation error when max chapters is out of range', () => {
        const { getByTestId, getByText, queryByText } = renderDialog();

        fireEvent.changeText(getByTestId('epub-max-input'), '5');
        fireEvent.press(getByTestId('epub-save-button'));

        expect(queryByText('Max chapters must be between 10 and 1000.')).toBeTruthy();
        expect(getByText('EPUB Settings')).toBeTruthy();
        expect(baseProps.onSave).not.toHaveBeenCalled();
    });

    it('shows validation error when range is invalid', () => {
        const { getByTestId, queryByText } = renderDialog();

        fireEvent.changeText(getByTestId('epub-range-start-input'), '20');
        fireEvent.changeText(getByTestId('epub-range-end-input'), '10');
        fireEvent.press(getByTestId('epub-save-button'));

        expect(queryByText('Range start cannot be greater than range end.')).toBeTruthy();
        expect(baseProps.onSave).not.toHaveBeenCalled();
    });

    it('saves parsed values when valid', async () => {
        const onSave = jest.fn();
        const onDismiss = jest.fn();
        const { getByTestId } = renderDialog({ onSave, onDismiss });

        fireEvent.changeText(getByTestId('epub-max-input'), '120');
        fireEvent.changeText(getByTestId('epub-range-start-input'), '5');
        fireEvent.changeText(getByTestId('epub-range-end-input'), '50');
        fireEvent(getByTestId('epub-bookmark-switch'), 'valueChange', true);
        fireEvent.press(getByTestId('epub-save-button'));

        expect(onSave).toHaveBeenCalledWith({
            maxChaptersPerEpub: 120,
            rangeStart: 5,
            rangeEnd: 50,
            startAfterBookmark: true,
        });
        await waitFor(() => {
            expect(onDismiss).toHaveBeenCalledTimes(1);
        });
    });

    it('disables bookmark toggle when no bookmark exists', () => {
        const { getByTestId } = renderDialog({ hasBookmark: false });
        const bookmarkSwitch = getByTestId('epub-bookmark-switch');

        expect(bookmarkSwitch.props.disabled).toBe(true);
    });
});
