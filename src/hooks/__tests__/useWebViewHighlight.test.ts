
import { renderHook, waitFor } from '@testing-library/react-native';
import { useWebViewHighlight } from '../useWebViewHighlight';
import { WebView } from 'react-native-webview';

describe('useWebViewHighlight', () => {
    const mockInjectJavaScript = jest.fn();
    const mockWebViewRef = {
        current: {
            injectJavaScript: mockInjectJavaScript,
        } as unknown as WebView,
    };

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should not inject JavaScript when webView ref is null', () => {
        const nullRef: any = { current: null };

        renderHook(() => useWebViewHighlight(nullRef, 0, true));

        expect(mockInjectJavaScript).not.toHaveBeenCalled();
    });

    it('should not inject JavaScript when chunk index is not an integer', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, 1.5, true));

        expect(mockInjectJavaScript).not.toHaveBeenCalled();
    });

    it('should not inject JavaScript when chunk index is negative', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, -1, true));

        expect(mockInjectJavaScript).not.toHaveBeenCalled();
    });

    it('should remove highlight when controller is not visible', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, 0, false));

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('tts-active')
        );
        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('classList.remove')
        );
    });

    it('should add highlight for valid chunk index when controller is visible', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, 0, true));

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining(`data-tts-group="0"`)
        );
        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('classList.add')
        );
    });

    it('should remove old highlights before adding new ones', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, 2, true));

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('querySelectorAll')
        );
        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('.tts-active')
        );
    });

    it('should scroll element into view when highlighting', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, 0, true));

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('scrollIntoView')
        );
    });

    it('should handle different chunk indices', () => {
        const { rerender } = renderHook(
            (props: any) => useWebViewHighlight(mockWebViewRef, props.chunkIndex, true),
            { initialProps: { chunkIndex: 0 } }
        );

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining(`data-tts-group="0"`)
        );

        mockInjectJavaScript.mockClear();

        rerender({ chunkIndex: 5 });

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining(`data-tts-group="5"`)
        );
    });

    it('should toggle highlight when controller visibility changes', () => {
        const { rerender } = renderHook(
            (props: any) => useWebViewHighlight(mockWebViewRef, 0, props.isVisible),
            { initialProps: { isVisible: true } }
        );

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('classList.add')
        );

        mockInjectJavaScript.mockClear();

        rerender({ isVisible: false });

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('classList.remove')
        );
        expect(mockInjectJavaScript).not.toHaveBeenCalledWith(
            expect.stringContaining('classList.add')
        );
    });

    it('should handle error in JavaScript injection gracefully', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, 0, true));

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('try')
        );
        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining('catch')
        );
    });

    it('should update highlight when chunk index changes', () => {
        const { rerender } = renderHook(
            (props: any) => useWebViewHighlight(mockWebViewRef, props.chunkIndex, true),
            { initialProps: { chunkIndex: 0 } }
        );

        mockInjectJavaScript.mockClear();

        rerender({ chunkIndex: 1 });

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining(`data-tts-group="1"`)
        );
    });

    it('should not scroll when highlighting hidden chunks', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, -1, false));

        expect(mockInjectJavaScript).not.toHaveBeenCalledWith(
            expect.stringContaining('scrollIntoView')
        );
    });

    it('should handle zero chunk index', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, 0, true));

        expect(mockInjectJavaScript).toHaveBeenCalledWith(
            expect.stringContaining(`data-tts-group="0"`)
        );
    });

    it('should handle NaN chunk index', () => {
        renderHook(() => useWebViewHighlight(mockWebViewRef, NaN, true));

        expect(mockInjectJavaScript).not.toHaveBeenCalled();
    });
});
