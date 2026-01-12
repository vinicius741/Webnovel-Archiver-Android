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

    // Add spaces after block elements to ensure they don't run together
    $('p, div, br, h1, h2, h3, h4, h5, h6, li').each((_, elem) => {
        $(elem).append(' ');
    });

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

/**
 * Removes unwanted sentences from content.
 */
export const removeUnwantedSentences = (content: string, sentenceRemovalList: string[]): string => {
    let cleanContent = content;
    for (const sentence of sentenceRemovalList) {
        // Using split/join for global replacement of exact string.
        cleanContent = cleanContent.split(sentence).join('');
    }
    return cleanContent;
};

/**
 * Extracts formatted text from HTML content, preserving paragraph breaks and structure.
 * Removes HTML tags while keeping the text layout intact.
 */
export const extractFormattedText = (html: string): string => {
    if (!html) return '';

    const $ = cheerio.load(html);

    // Remove unwanted elements
    $('script, style, iframe, noscript').remove();

    // Block elements that should create line breaks
    const blockElements = ['p', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'li', 'blockquote', 'tr'];
    // Block elements that end with extra blank line
    const majorBlockElements = ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote'];

    // Clone the body to work with it
    const $body = $('body').clone();

    // Replace br tags with newlines
    $body.find('br').replaceWith('\n');

    // Replace block elements with their content plus newlines
    blockElements.forEach(tag => {
        $body.find(tag).each((_, el) => {
            const $el = $(el);
            const content = $el.html() || '';
            const suffix = majorBlockElements.includes(tag) ? '\n\n' : '\n';
            $el.replaceWith(content + suffix);
        });
    });

    // Get text and clean up while preserving structure
    let result = $body.text()
        .replace(/[ \t]+/g, ' ')           // Collapse multiple spaces/tabs to single space
        .replace(/ \n/g, '\n')             // Remove space before newline
        .replace(/\n +/g, '\n')            // Remove spaces after newline
        .replace(/\n{3,}/g, '\n\n')        // Limit consecutive newlines to 2
        .trim();

    return result;
};

/**
 * Cleans chapter titles by removing time-ago suffixes and absolute dates.
 */
export const cleanChapterTitle = (title: string): string => {
    if (!title) return '';

    // Removes time-ago suffixes like "2 days ago", "5 hours ago", etc.
    let cleaned = title.replace(/\s*(?:[-–|]\s*)?\(?\s*(?:\d+|an?)\s+(?:second|minute|hour|day|week|month|year)s?\s+ago\s*\)?\s*$/i, '');

    // Remove absolute dates if at the end, e.g. "Title Nov 25, 2025" or "Title - Nov 25, 2025"
    cleaned = cleaned.replace(/\s*(?:[-–|]\s*)?\(?\s*(?:(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{1,2}(?:st|nd|rd|th)?,?\s+\d{4}|\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{4})\s*\)?\s*$/i, '');

    return cleaned.trim();
};
