import { load } from "cheerio";
import { Story, Chapter } from "../../types";
import { readChapterFile, checkFileExists } from "../storage/fileSystem";
import { EpubMetadataGenerator } from "./EpubMetadataGenerator";

export interface EpubCoverAsset {
  href: string;
  mediaType: string;
}

export class EpubContentProcessor {
  public static generateCoverHtml(
    story: Story,
    coverAsset?: EpubCoverAsset,
  ): string {
    const escapedTitle = EpubMetadataGenerator.escapeXml(story.title);
    const coverContent = coverAsset
      ? `<img class="cover-image" src="${coverAsset.href}" alt="${escapedTitle} cover"/>`
      : `<div class="cover-placeholder">
            <p>No cover image available</p>
        </div>`;

    return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
  "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>${escapedTitle} Cover</title>
    <link href="style.css" type="text/css" rel="stylesheet"/>
</head>
<body class="cover-page">
    <div class="cover-frame">
        ${coverContent}
    </div>
</body>
</html>`;
  }

  public static generateDetailsHtml(story: Story): string {
    const description = story.description?.trim();
    const tags = story.tags?.filter((tag) => tag.trim().length > 0) ?? [];
    const tagItems = tags
      .map(
        (tag) =>
          `<span class="tag">${EpubMetadataGenerator.escapeXml(tag)}</span>`,
      )
      .join("\n            ");
    const descriptionContent = description
      ? `<p>${EpubMetadataGenerator.escapeXml(description).replace(/\n+/g, "</p>\n            <p>")}</p>`
      : '<p class="muted">No description available.</p>';
    const tagsContent =
      tags.length > 0
        ? `<div class="tags">
            ${tagItems}
        </div>`
        : '<p class="muted">No tags available.</p>';

    return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
  "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>${EpubMetadataGenerator.escapeXml(story.title)} Details</title>
    <link href="style.css" type="text/css" rel="stylesheet"/>
</head>
<body class="details-page">
    <h1>${EpubMetadataGenerator.escapeXml(story.title)}</h1>
    <p class="byline">by ${EpubMetadataGenerator.escapeXml(story.author)}</p>
    <div class="details-section">
        <h2>Description</h2>
        <div class="description">
            ${descriptionContent}
        </div>
    </div>
    <div class="details-section">
        <h2>Tags</h2>
        ${tagsContent}
    </div>
</body>
</html>`;
  }

  public static generateTocHtml(story: Story, chapters: Chapter[]): string {
    const listItems = chapters
      .map(
        (c, i) =>
          `<li><a href="chapter_${i + 1}.xhtml">${EpubMetadataGenerator.escapeXml(c.title)}</a></li>`,
      )
      .join("\n        ");

    return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
  "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>Table of Contents</title>
    <link href="style.css" type="text/css" rel="stylesheet"/>
</head>
<body>
    <h1>Table of Contents</h1>
    <ul>
        ${listItems}
    </ul>
</body>
</html>`;
  }

  public static generateChapterHtml(chapter: Chapter, content: string): string {
    return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
  "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>${EpubMetadataGenerator.escapeXml(chapter.title)}</title>
    <link href="style.css" type="text/css" rel="stylesheet"/>
</head>
<body>
    <h2>${EpubMetadataGenerator.escapeXml(chapter.title)}</h2>
    <div class="content">
        ${content}
    </div>
</body>
</html>`;
  }

  public static generateCss(): string {
    return `
body { color: #1f2933; font-family: sans-serif; margin: 1em; }
h1, h2 { border-bottom: 1px solid #ccc; padding-bottom: 0.5em; text-align: center; }
p { line-height: 1.6; margin-bottom: 1em; }
ul { list-style-type: none; padding: 0; }
li { margin-bottom: 0.5em; }
a { color: #000; text-decoration: none; }
a:hover { text-decoration: underline; }
.cover-page { margin: 0; text-align: center; }
.cover-frame { height: 96vh; line-height: 96vh; text-align: center; width: 100%; }
.cover-image { max-height: 96vh; max-width: 100%; vertical-align: middle; }
.cover-placeholder { border: 1px solid #c8d0d8; line-height: 1.6; margin: 20vh auto 0; padding: 2em; width: 70%; }
.details-page { margin: 2em auto; max-width: 42em; }
.details-page h1 { border-bottom: 0; margin-bottom: 0.2em; }
.byline { color: #5b6773; font-size: 0.95em; margin-top: 0; text-align: center; }
.details-section { border-top: 1px solid #d9dee4; margin-top: 2em; padding-top: 1em; }
.details-section h2 { border-bottom: 0; font-size: 1.2em; margin-bottom: 0.8em; text-align: left; }
.description p { margin-top: 0; text-align: justify; }
.tags { line-height: 2.2; }
.tag { background: #eef2f7; border: 1px solid #c8d0d8; border-radius: 0.35em; display: inline-block; margin: 0 0.4em 0.5em 0; padding: 0.25em 0.65em; }
.muted { color: #6b7280; font-style: italic; }
        `;
  }

  public static sanitizeContent(html: string): string {
    const $ = load(html, { xmlMode: false });
    return $("body")
      .contents()
      .map((_, el) => $.xml(el))
      .get()
      .join("");
  }

  public static async readChapterContent(chapter: Chapter): Promise<string> {
    if (chapter.content) return chapter.content;

    if (chapter.filePath) {
      const text = await readChapterFile(chapter.filePath);
      if (text) {
        return this.sanitizeContent(text);
      }
      return `<p>[Error loading content for "${chapter.title}"]</p>`;
    }

    return `<p>[No content available]</p>`;
  }

  public static async checkChapterContentAvailability(
    chapter: Chapter,
  ): Promise<boolean> {
    if (chapter.content) return true;

    if (chapter.filePath) {
      return await checkFileExists(chapter.filePath);
    }

    return false;
  }
}
