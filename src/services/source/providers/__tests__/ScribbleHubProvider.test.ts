import { ScribbleHubProvider } from "../ScribbleHubProvider";
import { fetchFormPage } from "../../../network/fetcher";

jest.mock("../../../network/fetcher", () => ({
  fetchFormPage: jest.fn(),
}));

describe("ScribbleHubProvider", () => {
  const seriesUrl =
    "https://www.scribblehub.com/series/1056226/outrun--cyberpunk-litrpg/";

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("should parse metadata correctly", () => {
    const html = `
      <html>
        <head>
          <link rel="canonical" href="${seriesUrl}">
          <meta property="og:image" content="https://cdn.example.com/cover.jpg">
        </head>
        <body>
          <h1>Outrun - Cyberpunk LitRPG</h1>
          <span class="auth_name_fic"><a href="/profile/123/lostrain/">LostRain</a></span>
          <div class="wi_fic_desc">A neon cyberpunk story.</div>
          <span class="numscore">4.8</span>
          <div class="wi_fic_genre"><a href="/genre/action/">Action</a></div>
          <div class="wi_fic_tags"><a href="/tag/cyberpunk/">Cyberpunk</a></div>
        </body>
      </html>
    `;

    const metadata = ScribbleHubProvider.parseMetadata(html);

    expect(metadata).toEqual({
      title: "Outrun - Cyberpunk LitRPG",
      author: "LostRain",
      coverUrl: "https://cdn.example.com/cover.jpg",
      description: "A neon cyberpunk story.",
      tags: ["Action", "Cyberpunk"],
      score: "4.8",
      canonicalUrl: seriesUrl,
    });
  });

  it("should extract stable story and chapter IDs", () => {
    expect(ScribbleHubProvider.getStoryId(seriesUrl)).toBe("sh_1056226");
    expect(ScribbleHubProvider.getStoryId("https://www.scribblehub.com/bad")).toBe(
      "sh_url_https%3A%2F%2Fwww.scribblehub.com%2Fbad",
    );
    expect(
      ScribbleHubProvider.getChapterId?.(
        "https://www.scribblehub.com/read/1056226-outrun--cyberpunk-litrpg/chapter/2294631/",
      ),
    ).toBe("sh_2294631");
  });

  it("should match Scribble Hub series URLs", () => {
    expect(ScribbleHubProvider.isSource(seriesUrl)).toBe(true);
    expect(
      ScribbleHubProvider.isSource(
        "https://www.scribblehub.com/read/1056226-outrun--cyberpunk-litrpg/chapter/1/",
      ),
    ).toBe(false);
  });

  it("should parse the first TOC page and normalize newest-first order", async () => {
    const html = `
      <input id="mypostid" value="1056226">
      <ol class="toc_ol">
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/300/">Chapter 3</a><span>Jan 3</span></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/200/">Chapter 2</a><span>Jan 2</span></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/100/">Chapter 1</a><span>Jan 1</span></li>
      </ol>
    `;

    const chapters = await ScribbleHubProvider.getChapterList(html, seriesUrl);

    expect(chapters.map((chapter) => chapter.title)).toEqual([
      "Chapter 1",
      "Chapter 2",
      "Chapter 3",
    ]);
    expect(chapters.map((chapter) => chapter.id)).toEqual([
      "sh_100",
      "sh_200",
      "sh_300",
    ]);
    expect(chapters[0].url).toBe(
      "https://www.scribblehub.com/read/1056226-outrun--cyberpunk-litrpg/chapter/100/",
    );
  });

  it("should fetch paginated TOC pages and normalize the combined list", async () => {
    const html = `
      <input id="mypostid" value="1056226">
      <ul id="pagination-mesh-toc">
        <li>1</li>
        <li>2</li>
      </ul>
      <ol class="toc_ol">
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/400/">Chapter 4</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/300/">Chapter 3</a></li>
      </ol>
    `;
    (fetchFormPage as jest.Mock).mockResolvedValue(`
      <ol class="toc_ol">
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/200/">Chapter 2</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/100/">Chapter 1</a></li>
      </ol>
      0
    `);
    const onProgress = jest.fn();

    const chapters = await ScribbleHubProvider.getChapterList(
      html,
      seriesUrl,
      onProgress,
    );

    expect(fetchFormPage).toHaveBeenCalledWith(
      "https://www.scribblehub.com/wp-admin/admin-ajax.php",
      {
        action: "wi_getreleases_pagination",
        pagenum: 2,
        mypostid: "1056226",
      },
    );
    expect(onProgress).toHaveBeenCalledWith("Fetching chapter page 2/2...");
    expect(chapters.map((chapter) => chapter.id)).toEqual([
      "sh_100",
      "sh_200",
      "sh_300",
      "sh_400",
    ]);
  });

  it("should parse chapter content from chp_raw", () => {
    const html = `
      <div id="chp_raw">
        <div class="wi_authornotes">Author note</div>
        <p>Story paragraph.</p>
        <script>window.bad = true;</script>
      </div>
    `;

    const content = ScribbleHubProvider.parseChapterContent(html);

    expect(content).toContain("<p>Story paragraph.</p>");
    expect(content).not.toContain("Author note");
    expect(content).not.toContain("script");
  });
});
