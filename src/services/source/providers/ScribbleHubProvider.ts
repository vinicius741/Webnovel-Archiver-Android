import { load } from 'cheerio';
import { SourceProvider, NovelMetadata, ChapterInfo } from '../types';
import { fetchPage } from '../../network/fetcher';

export const ScribbleHubProvider: SourceProvider = {
    name: 'ScribbleHub',
    baseUrl: 'https://www.scribblehub.com',

    isSource: (url: string) => {
        return url.includes('scribblehub.com');
    },

    getStoryId: (url: string): string => {
        const shMatch = url.match(/series\/(\d+)/);
        if (shMatch) {
            return 'sh_' + shMatch[1];
        }
        return 'sh_' + Date.now();
    },

    parseMetadata: (html: string): NovelMetadata => {
        const $ = load(html);

        const title = $('div.fic_title').first().text().trim() || 'Unknown Title';
        const author = $('span.auth_name_fic').first().text().trim() || 'Unknown Author';

        let coverUrl: string | undefined;
        const shCover = $('div.fic_image img').first().attr('src');
        if (shCover) coverUrl = shCover;

        // Description
        const description = $('.wi_fic_desc').first().text().trim();

        // Tags
        const tags: string[] = [];
        $('.stag').each((_, el) => {
            const tag = $(el).text().trim();
            if (tag) {
                tags.push(tag);
            }
        });

        const uniqueTags = Array.from(new Set(tags));

        return {
            title,
            author,
            coverUrl,
            description,
            tags: uniqueTags.length > 0 ? uniqueTags : undefined
        };
    },

    getChapterList: async (html: string, baseUrl: string): Promise<ChapterInfo[]> => {
        let allChapters: ChapterInfo[] = [];
        let currentHtml = html;
        let pageCount = 1;
        const MAX_PAGES = 100;

        // Helper to parse a single page
        const parsePage = (htmlContent: string) => {
            const $ = load(htmlContent);
            const pageChapters: ChapterInfo[] = [];
            
            $('li.toc_w').each((_, element) => {
                const link = $(element).find('a.toc_a');
                if (link.length > 0) {
                    const title = link.text().trim();
                    let relativeUrl = link.attr('href') || '';

                    if (relativeUrl && !relativeUrl.startsWith('http')) {
                       if (relativeUrl.startsWith('/')) {
                            const urlObj = new URL(baseUrl);
                            relativeUrl = `${urlObj.origin}${relativeUrl}`;
                        } else {
                            relativeUrl = `${baseUrl}/${relativeUrl}`;
                        }
                    }

                    pageChapters.push({
                        title,
                        url: relativeUrl
                    });
                }
            });
            return pageChapters;
        };

        // First page
        const firstPageChapters = parsePage(currentHtml);
        allChapters = [...firstPageChapters];

        // If we found no chapters, return early
        if (allChapters.length === 0) return allChapters;

        while (pageCount < MAX_PAGES) {
            const $ = load(currentHtml);
            let nextUrl: string | undefined;

            // Strategy 1: Look for "Next" or "»" link
            const nextLink = $('a.page-link').filter((_, el) => {
                const text = $(el).text().trim();
                return text === '»' || text.toLowerCase().includes('next');
            }).first();

            if (nextLink.length > 0) {
                const href = nextLink.attr('href');
                if (href) {
                     if (href.startsWith('http')) {
                         nextUrl = href;
                     } else {
                         // Resolve relative URL
                         const urlObj = new URL(baseUrl);
                         // Handle if href is just query param like "?toc=2"
                         if (href.startsWith('?')) {
                             // We need to be careful not to double up if baseUrl already has query
                             // But usually the href on the page is relative to the current path.
                             // Let's use the URL constructor with the CURRENT url context if possible, 
                             // but we only have baseUrl (which is the starting URL).
                             // Ideally we track 'currentUrl'.
                             // For simplicity, let's append to the origin + pathname of baseUrl.
                             nextUrl = `${urlObj.origin}${urlObj.pathname}${href}`;
                         } else if (href.startsWith('/')) {
                             nextUrl = `${urlObj.origin}${href}`;
                         } else {
                             nextUrl = `${urlObj.origin}${urlObj.pathname}/${href}`; // This might be wrong if no trailing slash
                         }
                     }
                }
            }

            // Strategy 2: Fallback - Construct URL manually if no link found
            // Only do this if we suspect there are more chapters (e.g. current page was full ~15 items)
            // But we can just try fetching the next page blindly if Strategy 1 failed.
            if (!nextUrl) {
                // Construct next page URL: baseUrl + ?toc=pageCount+1
                try {
                    const urlObj = new URL(baseUrl);
                    urlObj.searchParams.set('toc', String(pageCount + 1));
                    nextUrl = urlObj.toString();
                    console.log(`[ScribbleHub] Strategy 2: Guessing next URL ${nextUrl}`);
                } catch (e) {
                    console.error('[ScribbleHub] URL construction failed', e);
                    break;
                }
            }

            if (nextUrl) {
                console.log(`[ScribbleHub] Fetching page ${pageCount + 1}: ${nextUrl}`);
                try {
                    const nextHtml = await fetchPage(nextUrl);
                    const newChapters = parsePage(nextHtml);

                    if (newChapters.length === 0) {
                        console.log('[ScribbleHub] No chapters on next page. Stopping.');
                        break;
                    }

                    // Loop detection: Check if the first chapter of new page exists in allChapters
                    // This prevents infinite loops if the site redirects to the last page or similar
                    const firstNew = newChapters[0];
                    if (allChapters.some(c => c.url === firstNew.url)) {
                        console.log('[ScribbleHub] Detected duplicate chapters. Stopping.');
                        break;
                    }

                    allChapters = [...allChapters, ...newChapters];
                    currentHtml = nextHtml;
                    pageCount++;
                    
                    // Safety break if we fetched a page with very few chapters, implying end of list?
                    // Not necessarily, sometimes last page has 1 item.
                    
                } catch (e) {
                    console.error(`[ScribbleHub] Failed to fetch page ${pageCount + 1}`, e);
                    break;
                }
            } else {
                break;
            }
        }

        return allChapters;
    },

    parseChapterContent: (html: string): string => {
        const $ = load(html);
        
        // Content container
        const content = $('#chp_raw');

        // Remove ads or junk if any
        content.find('div.rv_ad').remove();
        content.find('.wi_author_notes').remove(); // Optional: remove author notes if desired? usually people want them.
        // Let's keep author notes for now, or maybe make it optional later.
        
        return content.html() || 'No content found';
    }
};
