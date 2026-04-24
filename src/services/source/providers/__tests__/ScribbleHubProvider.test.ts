import { ScribbleHubProvider } from "../ScribbleHubProvider";
import { fetchFormPage } from "../../../network/fetcher";

jest.mock("../../../network/fetcher", () => ({
  fetchFormPage: jest.fn(),
}));

describe("ScribbleHubProvider", () => {
  const seriesUrl =
    "https://www.scribblehub.com/series/1056226/outrun--cyberpunk-litrpg/";

  beforeEach(() => {
    jest.resetAllMocks();
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
    (fetchFormPage as jest.Mock)
      .mockResolvedValueOnce(`
        <ol class="toc_ol">
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/200/">Chapter 2</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/100/">Chapter 1</a></li>
        </ol>
        0
      `)
      .mockResolvedValueOnce("");
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

  it("should keep fetching TOC pages when the visible pagination is incomplete", async () => {
    const html = `
      <input id="mypostid" value="1056226">
      <ol class="toc_ol">
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/315/">Chapter 315</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/314/">Chapter 314</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/313/">Chapter 313</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/312/">Chapter 312</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/311/">Chapter 311</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/310/">Chapter 310</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/309/">Chapter 309</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/308/">Chapter 308</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/307/">Chapter 307</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/306/">Chapter 306</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/305/">Chapter 305</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/304/">Chapter 304</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/303/">Chapter 303</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/302/">Chapter 302</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/301/">Chapter 301</a></li>
      </ol>
    `;
    (fetchFormPage as jest.Mock)
      .mockResolvedValueOnce(`
        <ol class="toc_ol">
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/300/">Chapter 300</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/299/">Chapter 299</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/298/">Chapter 298</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/297/">Chapter 297</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/296/">Chapter 296</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/295/">Chapter 295</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/294/">Chapter 294</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/293/">Chapter 293</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/292/">Chapter 292</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/291/">Chapter 291</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/290/">Chapter 290</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/289/">Chapter 289</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/288/">Chapter 288</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/287/">Chapter 287</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/286/">Chapter 286</a></li>
        </ol>
        0
      `)
      .mockResolvedValueOnce(`
        <ol class="toc_ol">
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/285/">Chapter 285</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/284/">Chapter 284</a></li>
        </ol>
        0
      `);

    const chapters = await ScribbleHubProvider.getChapterList(html, seriesUrl);

    expect(fetchFormPage).toHaveBeenNthCalledWith(
      1,
      "https://www.scribblehub.com/wp-admin/admin-ajax.php",
      {
        action: "wi_getreleases_pagination",
        pagenum: 2,
        mypostid: "1056226",
      },
    );
    expect(fetchFormPage).toHaveBeenNthCalledWith(
      2,
      "https://www.scribblehub.com/wp-admin/admin-ajax.php",
      {
        action: "wi_getreleases_pagination",
        pagenum: 3,
        mypostid: "1056226",
      },
    );
    expect(chapters).toHaveLength(32);
    expect(chapters[0].id).toBe("sh_284");
    expect(chapters[chapters.length - 1].id).toBe("sh_315");
  });

  it("should stop when a later AJAX page only repeats existing chapters", async () => {
    const html = `
      <input id="mypostid" value="1056226">
      <ol class="toc_ol">
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/115/">Chapter 115</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/114/">Chapter 114</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/113/">Chapter 113</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/112/">Chapter 112</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/111/">Chapter 111</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/110/">Chapter 110</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/109/">Chapter 109</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/108/">Chapter 108</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/107/">Chapter 107</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/106/">Chapter 106</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/105/">Chapter 105</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/104/">Chapter 104</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/103/">Chapter 103</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/102/">Chapter 102</a></li>
        <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/101/">Chapter 101</a></li>
      </ol>
    `;
    (fetchFormPage as jest.Mock)
      .mockResolvedValueOnce(`
        <ol class="toc_ol">
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/100/">Chapter 100</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/99/">Chapter 99</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/98/">Chapter 98</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/97/">Chapter 97</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/96/">Chapter 96</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/95/">Chapter 95</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/94/">Chapter 94</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/93/">Chapter 93</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/92/">Chapter 92</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/91/">Chapter 91</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/90/">Chapter 90</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/89/">Chapter 89</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/88/">Chapter 88</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/87/">Chapter 87</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/86/">Chapter 86</a></li>
        </ol>
        0
      `)
      .mockResolvedValueOnce(`
        <ol class="toc_ol">
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/100/">Chapter 100</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/99/">Chapter 99</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/98/">Chapter 98</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/97/">Chapter 97</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/96/">Chapter 96</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/95/">Chapter 95</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/94/">Chapter 94</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/93/">Chapter 93</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/92/">Chapter 92</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/91/">Chapter 91</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/90/">Chapter 90</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/89/">Chapter 89</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/88/">Chapter 88</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/87/">Chapter 87</a></li>
          <li><a href="/read/1056226-outrun--cyberpunk-litrpg/chapter/86/">Chapter 86</a></li>
        </ol>
        0
      `);

    const chapters = await ScribbleHubProvider.getChapterList(html, seriesUrl);

    expect(fetchFormPage).toHaveBeenCalledTimes(2);
    expect(chapters).toHaveLength(30);
    expect(chapters[0].id).toBe("sh_86");
    expect(chapters[chapters.length - 1].id).toBe("sh_115");
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
