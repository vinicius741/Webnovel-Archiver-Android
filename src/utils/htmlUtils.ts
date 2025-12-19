import * as cheerio from 'cheerio';

/**
 * Extracts plain text from HTML content, suitable for TTS.
 * Removes scripts, styles, and other non-textual elements.
 */
export const extractPlainText = (html: string): string => {
    if (!html) return '';

    const $ = cheerio.load(html);

    // Remove unwanted elements
    $('script, style, iframe, noscript').remove();

    // Get text and clean up whitespace
    return $('body').text()
        .replace(/\s+/g, ' ')
        .trim();
};

/**
 * Prepares HTML content for TTS by grouping elements into chunks
 * and adding data attributes for highlighting.
 */
export const prepareTTSContent = (html: string, chunkSize: number = 500): { processedHtml: string, chunks: string[] } => {
    if (!html) return { processedHtml: '', chunks: [] };

    const $ = cheerio.load(html);
    const chunks: string[] = [];
    let currentChunkText = '';
    let currentGroupIndex = 0;

    // Select text-containing block elements
    // We prioritize keeping paragraphs together
    const elements = $('p, h1, h2, h3, h4, h5, h6, li, blockquote, div');

    elements.each((_, elem) => {
        // Skip divs that are just containers (have block children)
        if ($(elem).is('div') && $(elem).children('p, div, h1, h2, h3, h4, h5, h6, ul, ol, blockquote').length > 0) {
            return;
        }

        const text = $(elem).text().replace(/\s+/g, ' ').trim();
        if (!text) return;

        // If adding this text would exceed chunk size (and we have something in buffer),
        // push the current buffer and start a new one
        if (currentChunkText.length > 0 && (currentChunkText.length + text.length > chunkSize)) {
            chunks.push(currentChunkText);
            currentChunkText = '';
            currentGroupIndex++;
        }

        // Add the group identifier to the element
        $(elem).attr('data-tts-group', currentGroupIndex.toString());
        $(elem).addClass('tts-chunk');

        if (currentChunkText.length > 0) {
            currentChunkText += ' ' + text;
        } else {
            currentChunkText = text;
        }
    });

    // Push the remaining buffer
    if (currentChunkText.length > 0) {
        chunks.push(currentChunkText);
    }

    // If no chunks were found (e.g. plain text or structure not matched), falls back to body text
    if (chunks.length === 0) {
        const bodyText = $('body').text().replace(/\s+/g, ' ').trim();
        if (bodyText) {
            const $body = $('body');
            $body.attr('data-tts-group', '0');
            $body.addClass('tts-chunk');
            return {
                processedHtml: $body.html() || html,
                chunks: [bodyText]
            };
        }
    }

    return {
        processedHtml: $('body').html() || html,
        chunks
    };
};
