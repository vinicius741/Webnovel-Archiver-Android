import { useEffect } from 'react';
import { WebView } from 'react-native-webview';

export const useWebViewHighlight = (
    webViewRef: React.RefObject<WebView>,
    currentChunkIndex: number,
    isControllerVisible: boolean
) => {
    useEffect(() => {
        if (!webViewRef.current) return;

        if (typeof currentChunkIndex !== 'number' || !Number.isInteger(currentChunkIndex) || currentChunkIndex < 0) return;

        if (!isControllerVisible) {
            const js = `
                (function() {
                    const actives = document.querySelectorAll('.tts-active');
                    for (let i = 0; i < actives.length; i++) {
                        actives[i].classList.remove('tts-active');
                    }
                })();
            `;
            webViewRef.current.injectJavaScript(js);
            return;
        }

        const js = `
            (function() {
                try {
                    const actives = document.querySelectorAll('.tts-active');
                    for (let i = 0; i < actives.length; i++) {
                        actives[i].classList.remove('tts-active');
                    }
                    
                    const elements = document.querySelectorAll('[data-tts-group="${currentChunkIndex}"]');
                    for (let i = 0; i < elements.length; i++) {
                        elements[i].classList.add('tts-active');
                    }
                    
                    if (elements.length > 0) {
                        elements[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
                    }
                } catch (e) {
                }
            })();
        `;
        webViewRef.current.injectJavaScript(js);
    }, [currentChunkIndex, isControllerVisible]);
};
