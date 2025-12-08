import { load } from 'cheerio';

export interface ChapterInfo {
    title: string;
    url: string;
    chapterNumber?: number; // Optional
}

export const parseChapterList = (html: string, baseUrl: string): ChapterInfo[] => {
    const $ = load(html);
    const chapters: ChapterInfo[] = [];

    // RoyalRoad: Table rows with class 'chapter-row'
    // This is approximate; might need adjustment
    $('.chapter-row').each((_, element) => {
        const link = $(element).find('a[href*="/fiction/"]');
        if (link.length > 0) {
            const title = link.text().trim();
            let relativeUrl = link.attr('href') || '';

            // Normalize URL
            if (relativeUrl && !relativeUrl.startsWith('http')) {
                // Remove trailing/leading slashes to generic join
                // For now, assuming baseUrl does not have trailing slash
                // RR links are usually relative e.g., /fiction/123/chapter/456
                if (relativeUrl.startsWith('/')) {
                    const urlObj = new URL(baseUrl);
                    relativeUrl = `${urlObj.origin}${relativeUrl}`;
                } else {
                    relativeUrl = `${baseUrl}/${relativeUrl}`; // basic join
                }
            }

            chapters.push({
                title,
                url: relativeUrl
            });
        }
    });

    return chapters;
};
