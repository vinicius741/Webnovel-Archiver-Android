import React from 'react';
import { render } from '@testing-library/react-native';
import { ReaderContent } from '../ReaderContent';

jest.mock('react-native-webview', () => ({
    WebView: 'WebView',
}));

jest.mock('react-native-paper', () => ({
    ...jest.requireActual('react-native-paper'),
    useTheme: jest.fn().mockReturnValue({
        colors: {
            surface: '#ffffff',
            onSurface: '#000000',
            primary: '#6200ee',
        },
    }),
}));

describe('ReaderContent', () => {
    const mockWebViewRef = { current: null } as React.RefObject<any>;

    it('should generate HTML with CSS styling and processed content', () => {
        const { UNSAFE_getByType } = render(
            <ReaderContent webViewRef={mockWebViewRef} processedContent="<p>Test</p>" />
        );

        const WebView = require('react-native-webview').WebView;
        const webView = UNSAFE_getByType(WebView);

        expect(webView.props.source).toBeDefined();
        expect(webView.props.source.html).toContain('<p>Test</p>');
        expect(webView.props.source.html).toContain('background-color: #ffffff');
        expect(webView.props.source.html).toContain('color: #000000');
        expect(webView.props.source.html).toContain('.tts-active');
    });

    it('should return empty string for empty processed content', () => {
        const { UNSAFE_getByType } = render(
            <ReaderContent webViewRef={mockWebViewRef} processedContent="" />
        );

        const WebView = require('react-native-webview').WebView;
        const webView = UNSAFE_getByType(WebView);

        expect(webView.props.source.html).toBe('');
    });

    it('should handle large content', () => {
        const largeContent = '<p>' + 'x'.repeat(10000) + '</p>';
        const { UNSAFE_getByType } = render(
            <ReaderContent webViewRef={mockWebViewRef} processedContent={largeContent} />
        );

        const WebView = require('react-native-webview').WebView;
        const webView = UNSAFE_getByType(WebView);

        expect(webView.props.source.html).toContain('<p>');
        expect(webView.props.source.html).toContain('x'.repeat(1000));
    });
});
