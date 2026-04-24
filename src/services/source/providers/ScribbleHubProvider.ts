import { load } from "cheerio";
import { sanitizeTitle } from "../../../utils/stringUtils";
import { fetchFormPage } from "../../network/fetcher";
import { ChapterInfo, NovelMetadata, SourceProvider } from "../types";

const SCRIBBLE_HUB_BASE_URL = "https://www.scribblehub.com";
const SCRIBBLE_HUB_AJAX_URL =
  "https://www.scribblehub.com/wp-admin/admin-ajax.php";
const SCRIBBLE_HUB_MAX_TOC_PAGES = 500;

const toAbsoluteUrl = (href: string, baseUrl: string): string => {
  if (!href) return "";

  try {
    return new URL(href, baseUrl).toString();
  } catch {
    return href;
  }
};

const unique = (values: string[]): string[] => {
  return Array.from(new Set(values.filter(Boolean)));
};

const stableFallbackId = (url: string): string => {
  return `sh_url_${encodeURIComponent(url.toLowerCase())}`;
};

const normalizeAjaxPageHtml = (html: string): string => {
  return html.replace(/0\s*$/, "").trim();
};

const parseTocItems = (html: string, baseUrl: string): ChapterInfo[] => {
  const $ = load(html);
  const chapters: ChapterInfo[] = [];

  $(".toc_ol li").each((_, element) => {
    const link = $(element).find('a[href*="/read/"][href*="/chapter/"]').first();
    const href = link.attr("href");
    if (!href) return;

    const url = toAbsoluteUrl(href, baseUrl);
    const title = sanitizeTitle(link.text().trim());
    const chapterId = ScribbleHubProvider.getChapterId?.(url);

    chapters.push({
      id: chapterId,
      title: title || "Untitled Chapter",
      url,
    });
  });

  return chapters;
};

const parsePaginationPageCount = (html: string): number => {
  const $ = load(html);
  const pageNumbers: number[] = [];

  $("#pagination-mesh-toc li").each((_, element) => {
    const pageNumber = Number.parseInt($(element).text().trim(), 10);
    if (!Number.isNaN(pageNumber)) {
      pageNumbers.push(pageNumber);
    }
  });

  return pageNumbers.length > 0 ? Math.max(...pageNumbers) : 1;
};

export const ScribbleHubProvider: SourceProvider = {
  name: "Scribble Hub",
  baseUrl: SCRIBBLE_HUB_BASE_URL,

  isSource: (url: string) => {
    return /https?:\/\/(?:www\.)?scribblehub\.com\/series\/\d+/i.test(url);
  },

  getStoryId: (url: string): string => {
    const match = url.match(/\/series\/(\d+)/i);
    if (match) {
      return `sh_${match[1]}`;
    }
    return stableFallbackId(url);
  },

  getChapterId: (url: string): string | undefined => {
    const match = url.match(/\/chapter\/(\d+)/i);
    return match ? `sh_${match[1]}` : undefined;
  },

  parseMetadata: (html: string): NovelMetadata => {
    const $ = load(html);

    const title =
      $(".fic_title").first().text().trim() ||
      $(".wi_fic_title").first().text().trim() ||
      $("h1").first().text().trim() ||
      $('meta[property="og:title"]').attr("content") ||
      "Unknown Title";

    const author =
      $(".auth_name_fic a").first().text().trim() ||
      $('a[href*="/profile/"]').first().text().trim() ||
      $('meta[name="author"]').attr("content") ||
      "Unknown Author";

    const coverUrl =
      $(".fic_image img").first().attr("src") ||
      $(".fic_image img").first().attr("data-src") ||
      $('meta[property="og:image"]').attr("content") ||
      undefined;

    const description =
      $(".wi_fic_desc").first().text().trim() ||
      $(".fic_synopsis").first().text().trim() ||
      $("#synopsis").first().text().trim() ||
      $('meta[property="og:description"]').attr("content") ||
      $('meta[name="description"]').attr("content") ||
      undefined;

    const canonicalUrl =
      $('link[rel="canonical"]').attr("href") ||
      $('meta[property="og:url"]').attr("content") ||
      undefined;

    const tags: string[] = [];
    $(
      '.wi_fic_genre a, .wi_fic_tags a, .fic_genre a, .fic_tags a, a[href*="/genre/"], a[href*="/tag/"]',
    ).each((_, element) => {
      const tag = $(element).text().trim();
      if (tag) tags.push(tag);
    });
    const uniqueTags = unique(tags);

    const score =
      $(".numscore").first().text().trim() ||
      $('[itemprop="ratingValue"]').first().text().trim() ||
      $(".rate_fic_user").first().text().trim() ||
      undefined;

    return {
      title,
      author,
      coverUrl,
      description,
      tags: uniqueTags.length > 0 ? uniqueTags : undefined,
      score,
      canonicalUrl,
    };
  },

  getChapterList: async (
    html: string,
    baseUrl: string,
    onProgress?: (message: string) => void,
  ): Promise<ChapterInfo[]> => {
    onProgress?.("Parsing chapter list...");

    const $ = load(html);
    const postId = $("#mypostid").attr("value");
    const totalPages = parsePaginationPageCount(html);
    const chapters = parseTocItems(html, baseUrl);
    const seenChapterUrls = new Set(chapters.map((chapter) => chapter.url));
    const shouldFetchPaginatedChapters =
      Boolean(postId) && (totalPages > 1 || chapters.length >= 15);

    if (shouldFetchPaginatedChapters && postId) {
      for (let page = 2; page <= SCRIBBLE_HUB_MAX_TOC_PAGES; page++) {
        const progressLabel =
          totalPages > 1
            ? `Fetching chapter page ${page}/${totalPages}${
                page > totalPages ? "+" : ""
              }...`
            : `Fetching chapter page ${page}...`;

        onProgress?.(progressLabel);

        const pageHtml = await fetchFormPage(SCRIBBLE_HUB_AJAX_URL, {
          action: "wi_getreleases_pagination",
          pagenum: page,
          mypostid: postId,
        });

        const pageChapters = parseTocItems(
          normalizeAjaxPageHtml(pageHtml),
          baseUrl,
        );

        if (pageChapters.length === 0) {
          break;
        }

        const newChapters = pageChapters.filter((chapter) => {
          if (seenChapterUrls.has(chapter.url)) {
            return false;
          }

          seenChapterUrls.add(chapter.url);
          return true;
        });

        if (newChapters.length === 0) {
          break;
        }

        chapters.push(...newChapters);

        if (pageChapters.length < 15) {
          break;
        }
      }
    }

    return [...chapters].reverse();
  },

  parseChapterContent: (html: string): string => {
    const $ = load(html);
    const content = $("#chp_raw").first();

    content.find("script").remove();
    content.find("style").remove();
    content.find(".wi_authornotes").remove();
    content.find(".sharedaddy").remove();
    content.find(".code-block").remove();

    return content.html()?.trim() || "No content found";
  },
};
