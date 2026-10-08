# Patreon Early-Access Chapters

Many authors publish chapters on Patreon weeks before the public release. A story from any source
can be linked to one collection on its creator's Patreon; each sync then stores the paid chapters
the user's own Patreon account can read, after the public chapters, and retires each copy once the
public chapter that publishes it has downloaded.

This is a supplement to a source story, not a novel source: Patreon has no `SourceProvider` and is
not in `SourceRegistry`.

## Signing in (Google accounts included)

Google refuses sign-in inside embedded WebViews, and a Custom Tab's cookies belong to the browser,
so the app cannot run Patreon's login itself. Instead the user signs in with a real browser and
hands the app that browser's `session_id` cookie:

1. In Firefox for Android, install the Cookie-Editor add-on and sign in on patreon.com (Google
   sign-in works). Desktop Chrome DevTools → Application → Cookies works as well.
2. Copy the `session_id` value.
3. In the app: **Settings → Data & Backup → Patreon Account**, paste it, and **Save**. The dialog
   verifies the session against `/api/current_user` and lists the creators the account pays.

`source/PatreonSession.kt` stores the cookie only in the shared `android.webkit.CookieManager`, so
`AndroidCookieJar` sends it on Patreon requests. It never enters story JSON, backups, diagnostics,
or logs, and the field is masked. **Sign out** clears it. When Patreon rejects the cookie (401/403),
the link status asks the user to paste a new one; the public sync still finishes.

## Linking a novel

The Details Patreon card (shown when the source page links a Patreon) has an **Early-access
chapters** row. Tapping it loads the creator's public collection list (`/api/collection`) and
ranks the collection sharing the most title words with the novel first. Picking one saves a
`PatreonEarlyAccessLink` (campaign id, collection id/title) on the story and starts a sync.
Switching to another collection or unlinking removes the stored copies of the old one.

## What a sync does

`sync/StorySyncEngine` keeps Patreon copies out of the source merge entirely: chapters with a
`patreonPostId` are split off before `mergeChapters`/`mergeLatestChapters`, so they never count as
removed source chapters (which would create an archive snapshot). After the source merge,
`sync/PatreonChapterSync`:

1. Reads the linked collection newest-first (`/api/posts`, 20 per page, `filter[collection_id]`)
   until a page contains a post matching a public chapter, capped at 10 pages. The post list
   already carries each post's full body (`content_json_string`), so no per-chapter request exists.
2. Splits the posts at the newest one already public (`PatreonChapterPlanning.selectEarlyAccess`);
   posts without a chapter-like title (announcements) are ignored. When no post matches any public
   chapter, nothing is added and the link explains why.
3. Converts readable bodies (`PatreonRichText`, a ProseMirror document: paragraphs, headings,
   breaks, lists, bold/italic/underline/strike; images and embeds are dropped), applies the user's
   download cleanup rules, and writes each copy as a downloaded chapter whose file name is
   `NNNN_patreon_<postId>.html`, so it can never collide with a public chapter's file.
4. Records `lastCheckedAt`, the number of locked posts, and a user-facing error on the link.

Patreon copies are ordered by Patreon publication time and always sit after every public chapter,
so inserting new public chapters never shifts a public chapter's index. Patreon copies are never
queued for download (`DownloadQueuePlanning` skips them).

## Matching and replacement

`domain/story/PatreonCopyPlanning` parses titles into a key of book number, chapter number
(decimals such as `35.5` included), and a normalized subtitle:

- `Novel Name - Book 4 Chapter 43: Assembly` → book 4, chapter 43, `assembly`
- `Book 3 Chapter 10.5: Suffrage` → book 3, chapter 10.5
- `Chapter 62: An Understanding (End of Book 1)` → no book (a book only counts before the chapter
  token), chapter 62

Two keys match when book and chapter agree; when only one side names the book (sources often
restart numbering per book), the subtitle must agree too. Unnumbered titles (Prologue, Epilogue)
match on their normalized title. The novel-name prefix is dropped for display.

`reconcile` runs on every sync commit and on every download commit
(`AppRepository.completeDownloadedChapter`): each copy points at the public chapter that publishes
it (`patreonReplacedBy`), and a copy is removed — and its file deleted — only after that public
chapter has **downloaded**, so there is never a gap. A bookmark on a removed copy moves to the
public chapter.

## Compatibility

- New fields are optional with defaults: `Chapter.patreonPostId`, `Chapter.patreonReplacedBy`,
  `Story.patreonEarlyAccess`. Older data loads unchanged.
- Archive snapshots keep stored copies but drop the live link (archives never sync).
- Full backups include copy files like any downloaded chapter; the session cookie is never backed up.
- `StorySyncMergePlanning.foldConcurrentChanges` treats the link as user-owned: a link change during
  a sync wins, and the sync only refreshes the status of the same collection.

## Limits

- Patreon's web API is undocumented and may change. Requests go through the shared `NetworkClient`.
- Only text posts are supported; chapters shipped as PDF/DOCX attachments are not read.
- Newly stored copies do not appear in the Updates list, which tracks undownloaded chapters.
- TTS resume positions on a removed copy are not remapped.
