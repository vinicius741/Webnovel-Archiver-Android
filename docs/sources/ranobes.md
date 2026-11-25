# Ranobes

Ranobes (`ranobes.net`) is registered as `ranobes` through `RanobesProvider`. The Add Story
picker, source settings, library filters, and download scheduler discover it through
`SourceRegistry`.

## Imports and chapters

Paste a novel page such as
`https://ranobes.net/novels/752990-reincarnated-as-a-fox-with-system-v71134.html`. Two more
addresses import the same novel, so following one from any of its pages never creates a second
book:

- the same page at the site root,
  `https://ranobes.net/752990-reincarnated-as-a-fox-with-system-v71134.html`, which is where the
  site's own breadcrumbs point (the site redirects it to `/novels/`);
- a chapter page,
  `https://ranobes.net/reincarnated-as-a-fox-with-system-v71134-752990/3440587.html`, which names
  its novel in its path. Importing one adds the whole novel, from its first chapter.

Tracking parameters and fragments are discarded. The slug is kept as submitted: the site redirects
a wrong slug to the novel's page by id. The table of contents (`/chapters/<id>/`), its `/first`
shortcut, a bare `/novels/<id>.html` (a 404 on the site), tags, and catalog pages are not
importable.

Story IDs are `ranobes_<novel-id>` and chapter IDs are `ranobes_<chapter-id>`.

## Chapter lists

The novel page lists only the newest 25 chapters. The complete list is the table of contents at
`/chapters/<id>/`, which the site renders in the browser from JSON embedded in each page
(`script#chapters-data`), newest first and about 25 chapters to a page: a 1,220-chapter novel spans
49 pages. Every page repeats the page count, the total number of chapters, and its own page
number, and the provider relies on those three values.

- A full sync reads every page. It gives up rather than return a short list, which the merge would
  treat as the author having deleted every chapter it never read: it fails when the list runs past
  400 pages (10,000 chapters), when a page is not the one that was requested, or when fewer
  chapters were read than the site says exist. A chapter published while the pages are being read
  repeats at the seam between two pages and is kept once.
- An update check reads the novel page and the first page of the list, so it costs two requests
  whatever the novel's length. The list arrives newest first and is returned oldest first, as the
  sync's latest-chapter merge requires. If the table of contents is blocked, the novel page's own
  list answers instead; its dates are relative ("2 months ago") and stay estimates until the next
  full sync.

Chapter dates are UTC. The site prints them without a zone, but its `<time datetime>` element
carries `+00:00` for the same clock time.

## Metadata

The novel page supplies the title, the author list (joined with commas), the cover, and the
synopsis with the catalog line the site appends to every synopsis removed. Genres and "events"
become the story's tags. `Status in COO` becomes the publication status, and its text is kept as
the source status. `Ongoing` and `Completed` are the two values seen; any other wording leaves the
publication status to the one already stored.

| On the page | Stored as |
|-------------|-----------|
| Views, which the page calls unique views | `READERS` |
| Total views, which cover all chapters | `TOTAL_VIEWS` |
| Total comments, else the novel page's own Comments | `COMMENTS` |
| The count under the rating | `RATINGS` |
| Bookmarks | `BOOKMARKS` |
| The rating headline (`4`, `3.9`) | the score, as displayed |

The site separates thousands with spaces (`264 706`), where the shared counter reader would stop,
so the provider reads its own. The rating breakdown is published as percentages rather than
counts, so no rating distribution is stored. The page has no word count.

## Chapter text

The reader receives the chapter body as HTML with its formatting kept and its illustrations'
addresses made absolute. The advertisements the site injects between paragraphs are removed, as
are scripts, the title, the navigation, and the comments. A chapter page without a body, or with
an empty one, fails the download instead of saving a placeholder.

## Network and access

Requests keep 1.5 seconds apart within a budget of 30 a minute, and a `429` or `503` is retried
once through the sources' shared back-off. A first import of a 1,220-chapter novel is 50 requests.
These limits match the forum sources; the site's own tolerance has not been measured.

The site sits behind DDoS-Guard, not Cloudflare. The novel, table-of-contents and chapter pages
read for this integration on 24 November 2026, requested with the app's mobile User-Agent, came
back without a challenge. The app recognises only Cloudflare's challenges, so if DDoS-Guard ever
interposes one it will surface as a parse failure ("novel page was not recognized", "chapter
content not found") and not open the browser verification.

Guests can read everything a download needs. The novel's external links are hidden until sign-in,
so a Ranobes novel never picks up a Patreon link from its page and one has to be entered by hand.
No account-login flow is implemented.

`ranobes.top` serves the same catalog under its own address, behind Cloudflare. It is not
registered, and a URL on it is reported as unsupported.

## Validation

Synthetic fixtures under `src/test/resources/fixtures/ranobes/` keep the public site's novel,
table-of-contents, and chapter structure with invented content. `RanobesProviderTest` covers URL
ownership and normalization, metadata and counters, status, chapter-list order, dates and stable
ids, incomplete and withheld lists, the seam duplicate, the one-page update check and its
fallback, the latest-chapter merge against the stored novel, chapter text cleanup, and malformed
pages.

The same parser was run over novel, table-of-contents, and chapter pages downloaded from the site
on 24 November 2026, and its output was compared with those pages.

On 24 November 2026 the release gate (`:app:lintKotlin :app:releaseCheck`) passed with this
change, 2,128 unit tests among them. On the `webnovel_api36` debug build the offline `sources-all`
scenario passed: it imported the seven provider fixtures and downloaded 16 chapters, the three
Ranobes chapters among them. The Add Story picker, the library card, the Details page, and the
reader were checked on the inner and the cover displays.

Not run: an import from the live site through the app. Live site checks need an explicit request.
