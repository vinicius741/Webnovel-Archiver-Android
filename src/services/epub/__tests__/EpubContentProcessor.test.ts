import { EpubContentProcessor } from "../EpubContentProcessor";
import { Story, Chapter } from "../../../types";

jest.mock("../../storage/fileSystem");

describe("EpubContentProcessor", () => {
  const mockStory: Story = {
    id: "123",
    title: "Test Story",
    author: "Test Author",
    sourceUrl: "http://test.com",
    coverUrl: "http://cover.com",
    chapters: [],
    status: "idle",
    totalChapters: 0,
    downloadedChapters: 0,
    description: "A story about <magic> & survival.\nNew allies appear.",
    tags: ["Fantasy", "Adventure & Action"],
  };

  const mockChapters: Chapter[] = [
    { id: "c1", title: "Chapter 1", url: "http://c1", downloaded: true },
    { id: "c2", title: "Chapter 2", url: "http://c2", downloaded: true },
    { id: "c3", title: "Chapter 3", url: "http://c3", downloaded: true },
  ];

  describe("generateCoverHtml", () => {
    it("should generate cover page with embedded cover image", () => {
      const html = EpubContentProcessor.generateCoverHtml(mockStory, {
        href: "images/cover.jpg",
        mediaType: "image/jpeg",
      });

      expect(html).toContain('<?xml version="1.0"');
      expect(html).toContain('<body class="cover-page">');
      expect(html).toContain('src="images/cover.jpg"');
      expect(html).toContain('alt="Test Story cover"');
    });

    it("should generate cover placeholder when image is unavailable", () => {
      const html = EpubContentProcessor.generateCoverHtml(mockStory);

      expect(html).toContain("No cover image available");
      expect(html).toContain("cover-placeholder");
    });
  });

  describe("generateDetailsHtml", () => {
    it("should generate details page with description and tags", () => {
      const html = EpubContentProcessor.generateDetailsHtml(mockStory);

      expect(html).toContain("<h2>Description</h2>");
      expect(html).toContain("A story about &lt;magic&gt; &amp; survival.");
      expect(html).toContain("<h2>Tags</h2>");
      expect(html).toContain('<span class="tag">Fantasy</span>');
      expect(html).toContain(
        '<span class="tag">Adventure &amp; Action</span>',
      );
    });

    it("should generate empty-state copy for missing details", () => {
      const html = EpubContentProcessor.generateDetailsHtml({
        ...mockStory,
        description: undefined,
        tags: [],
      });

      expect(html).toContain("No description available.");
      expect(html).toContain("No tags available.");
    });
  });

  describe("generateTocHtml", () => {
    it("should generate TOC HTML with all chapters", () => {
      const toc = EpubContentProcessor.generateTocHtml(mockStory, mockChapters);

      expect(toc).toContain('<?xml version="1.0"');
      expect(toc).toContain("Table of Contents");
      expect(toc).toContain("chapter_1.xhtml");
      expect(toc).toContain("chapter_2.xhtml");
      expect(toc).toContain("chapter_3.xhtml");
    });

    it("should escape XML special characters in chapter titles", () => {
      const chaptersWithSpecialChars = [
        {
          id: "c1",
          title: "Chapter 1 & 2",
          url: "http://c1",
          downloaded: true,
        },
        { id: "c2", title: "Chapter <3>", url: "http://c2", downloaded: true },
      ];

      const toc = EpubContentProcessor.generateTocHtml(
        mockStory,
        chaptersWithSpecialChars,
      );

      expect(toc).toContain("Chapter 1 &amp; 2");
      expect(toc).toContain("Chapter &lt;3&gt;");
    });

    it("should generate empty TOC for no chapters", () => {
      const toc = EpubContentProcessor.generateTocHtml(mockStory, []);

      expect(toc).toContain("Table of Contents");
      expect(toc).not.toContain("chapter_");
    });
  });

  describe("generateChapterHtml", () => {
    it("should generate chapter HTML with content", () => {
      const chapter = mockChapters[0];
      const content = "<p>Chapter content here.</p>";
      const html = EpubContentProcessor.generateChapterHtml(chapter, content);

      expect(html).toContain('<?xml version="1.0"');
      expect(html).toContain("Chapter 1");
      expect(html).toContain('<div class="content">');
      expect(html).toContain(content);
    });

    it("should escape XML special characters in chapter title", () => {
      const chapter = {
        id: "c1",
        title: 'Chapter <1> & "Special"',
        url: "http://c1",
        downloaded: true,
      };
      const html = EpubContentProcessor.generateChapterHtml(chapter, "");

      expect(html).toContain("Chapter &lt;1&gt; &amp; &quot;Special&quot;");
    });
  });

  describe("generateCss", () => {
    it("should generate CSS styles", () => {
      const css = EpubContentProcessor.generateCss();

      expect(css).toContain("font-family: sans-serif");
      expect(css).toContain("text-align: center");
      expect(css).toContain("border-bottom: 1px solid #ccc");
      expect(css).toContain("line-height: 1.6");
    });
  });

  describe("sanitizeContent", () => {
    it("should extract body content from HTML", () => {
      const html =
        "<html><head><title>Test</title></head><body><p>Content</p></body></html>";
      const sanitized = EpubContentProcessor.sanitizeContent(html);

      expect(sanitized).toContain("<p>Content</p>");
      expect(sanitized).not.toContain("<head>");
      expect(sanitized).not.toContain("</html>");
    });

    it("should handle empty HTML", () => {
      const sanitized = EpubContentProcessor.sanitizeContent("");

      expect(sanitized).toBe("");
    });

    it("should handle HTML without body tag", () => {
      const html = "<p>Just content</p>";
      const sanitized = EpubContentProcessor.sanitizeContent(html);

      expect(sanitized).toContain("<p>Just content</p>");
    });
  });

  describe("readChapterContent", () => {
    it("should return chapter content if already available", async () => {
      const chapter = { ...mockChapters[0], content: "<p>Inline content</p>" };
      const content = await EpubContentProcessor.readChapterContent(chapter);

      expect(content).toContain("<p>Inline content</p>");
    });

    it("should read from file if content is not available", async () => {
      const { readChapterFile } = require("../../storage/fileSystem");
      readChapterFile.mockResolvedValue("<p>File content</p>");

      const chapter = { ...mockChapters[0], filePath: "/path/to/chapter.html" };
      const content = await EpubContentProcessor.readChapterContent(chapter);

      expect(readChapterFile).toHaveBeenCalledWith("/path/to/chapter.html");
      expect(content).toContain("<p>File content</p>");
    });

    it("should return error message if file read fails", async () => {
      const { readChapterFile } = require("../../storage/fileSystem");
      readChapterFile.mockResolvedValue(null);

      const chapter = { ...mockChapters[0], filePath: "/path/to/chapter.html" };
      const content = await EpubContentProcessor.readChapterContent(chapter);

      expect(content).toContain('[Error loading content for "Chapter 1"]');
    });

    it("should return no content message if no content or file path", async () => {
      const chapter = {
        id: "c1",
        title: "Chapter 1",
        url: "http://c1",
        downloaded: true,
      };
      const content = await EpubContentProcessor.readChapterContent(chapter);

      expect(content).toContain("[No content available]");
    });
  });

  describe("checkChapterContentAvailability", () => {
    it("should return true if content is available", async () => {
      const chapter = { ...mockChapters[0], content: "<p>Content</p>" };
      const available =
        await EpubContentProcessor.checkChapterContentAvailability(chapter);

      expect(available).toBe(true);
    });

    it("should return true if file exists", async () => {
      const { checkFileExists } = require("../../storage/fileSystem");
      checkFileExists.mockResolvedValue(true);

      const chapter = { ...mockChapters[0], filePath: "/path/to/chapter.html" };
      const available =
        await EpubContentProcessor.checkChapterContentAvailability(chapter);

      expect(available).toBe(true);
    });

    it("should return false if file does not exist", async () => {
      const { checkFileExists } = require("../../storage/fileSystem");
      checkFileExists.mockResolvedValue(false);

      const chapter = { ...mockChapters[0], filePath: "/path/to/chapter.html" };
      const available =
        await EpubContentProcessor.checkChapterContentAvailability(chapter);

      expect(available).toBe(false);
    });

    it("should return false if no content or file path", async () => {
      const chapter = {
        id: "c1",
        title: "Chapter 1",
        url: "http://c1",
        downloaded: true,
      };
      const available =
        await EpubContentProcessor.checkChapterContentAvailability(chapter);

      expect(available).toBe(false);
    });
  });
});
