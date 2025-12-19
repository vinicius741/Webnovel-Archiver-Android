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
