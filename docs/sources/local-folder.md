# Local folder

The **Local** source reads novels from a folder on the phone instead of a website, so books the
user already owns get the same reader, text-to-speech and pre-generated narration as web novels.
It is modeled on Mihon's local source: one folder, one subfolder per novel.

## Folder layout

```text
Novels/                      ← the folder picked in Add story › Local folder
├── The Quiet Harbor/        ← a novel: its book files, in natural name order
│   ├── Chapter 1.txt
│   ├── Chapter 2.txt
│   ├── Chapter 10.txt       ← after Chapter 2, not after Chapter 1
│   ├── cover.jpg            ← optional (cover/folder/poster .jpg/.png/.webp/.gif)
│   └── details.json         ← optional
├── Lanterns Over Vell.epub  ← a book file at the top is a novel by itself
└── Night Archive.pdf
```

Supported book files: EPUB, PDF, TXT, Markdown (`.md`) and HTML (`.html`, `.htm`, `.xhtml`).
Other files and dot-files are ignored, and a subfolder with no book files is not offered. Names
sort naturally without their extensions: `Chapter 12`, `Chapter 12.5`, `Chapter 13`.

## Chapters

Chapters come from each book's structure, not one per file:

- **EPUB**: every table-of-contents entry (EPUB 3 nav, else the EPUB 2 NCX) starts a chapter, which
  runs across spine documents the contents do not list. An entry pointing at an anchor splits its
  document there. Front matter before the first entry becomes a chapter only with at least 500
  characters of text (a cover or title page does not). With no table of contents, each spine
  document with text is a chapter. Images and links are removed; the text keeps its markup.
- **TXT / Markdown**: split at chapter headings (`Chapter 12`, `CHAPTER XIV`, `Chapter Twenty-One`,
  `Prologue`, `Epilogue`, `第12章`, Markdown `#`–`###`). A heading starts with a capital, uses
  upper-case roman numerals, and anything after the number is a separator or a short title-cased
  title, so hard-wrapped prose ("part I could not…", "Chapter 3 of the manual says…") is not one.
  Contents lines (`Chapter 1 …… 5`) and headings with nothing under them are dropped. A short line
  naming a part or volume just before a chapter heading becomes the chapter title's prefix. With
  fewer than two headings the file is one chapter, titled by a heading on its first line or by the
  file name; a heading-less file of 12,000+ words is cut into parts of about 6,000 words; an empty
  file adds nothing. Encoding: BOM, then UTF-8, then Windows-1252 (HTML honors its `<meta charset>`).
  Chapter keys come from the titles (a repeated title gets a counter), so a chapter added in the
  middle of a file does not change the ids of the chapters after it.
- **PDF**: the platform `PdfRenderer` text API reads each page (Android 15, API 35, or newer).
  `PdfTextReflow` drops page numbers and short running headers/footers
  at the top and bottom of each page (never inside the text), joins hyphenated and hard-wrapped lines into paragraphs, and keeps headings on their
  own lines; the text is then split like a TXT file.
- **Scanned PDF** (most pages have no text of their own: under 50 letters in the text layer, so a
  scanner's stamp, a page number or a watermark does not count): those pages are recognized on the
  phone (OCR, see below) and read like a text PDF. Before Android 15 every PDF's pages are
  recognized, since the text layer cannot be read. A text PDF's few blank pages (illustrations) are
  not recognized. A scan in which nothing is recognized fails clearly.
- **HTML**: one chapter per file, titled by its first `h1`/`h2` or `<title>`.

When a subfolder holds several multi-chapter books (volumes), their chapter titles are prefixed with
the book's title.

## Metadata

- Title: `details.json` › the subfolder name (folder novels) or the EPUB title / file name (book files).
- Status: `details.json` › completed (a book on the phone is finished; `unknown` would show Outdated
  a week after the last sync).
- Author, description, tags: `details.json` › the first EPUB's metadata.
- Cover: a cover image in the subfolder › the EPUB cover › the PDF's first page. The image is copied
  to app storage (`files/local_source_covers/`, named with a content hash so a changed cover shows).
- `details.json` uses Mihon's keys: `title`, `author` (or `artist`), `description`, `genre` (array or
  comma-separated string) and `status` (Mihon's numbers `1` ongoing, `2`/`4` completed, `5`/`6`
  hiatus, or words like "Completed").

## How it fits the app

- `source/local/LocalSourceProvider` is a `SourceProvider` with `SourceKind.LOCAL`. Its URLs are
  `local://library/<entry>[/<file>][#<part>]` (`LocalSourceUrls`), paths relative to the chosen
  folder, never device URIs, so choosing the folder again (new phone, restored backup) keeps story and
  chapter ids. Story ids are `local_` + a hash of the entry name; renaming a subfolder makes a new novel.
- Local sources are left out of `SourceRegistry.web()`: no browse row, per-site pace, web session or
  network host. The story page's source badge does not open a browser.
- Importing and Sync go through the normal `StorySyncEngine` path; `loadStory` rescans the files.
  `SyncDownloadPlanning` (with `readsEveryChapter`) then queues every chapter not yet in the library,
  on import and after every rescan, and the download queue reads them from the files without pacing.
  From there chapters are ordinary saved chapters: cleanup, the reader, TTS, narration, EPUB export
  and backups work unchanged.
- The folder is picked with the Storage Access Framework (`OpenDocumentTree`), and the persisted
  read grant and tree URI live in this device's `local_source` preferences (`SafLocalLibraryFiles`),
  not in the library JSON or backups. EPUB and PDF files are copied to `cache/local_source/` for
  random access; the last six copies are kept.
- `LocalNovelReader` keeps the last four parsed books, so reading a book's chapters parses it once,
  and reuses folder listings for 10 seconds while chapters are read. A scan parses one book at a
  time. Each book is parsed under its own lock: reads of one book wait for its parse (a scan's
  text recognition included), other books' do not. A book the app cannot read (a damaged or
  password-protected PDF, a PDF with no recognizable text, a broken EPUB) is skipped when others in
  the folder read; a failure to read the folder itself, or text recognition failing on a page,
  fails the scan, so a sync never marks chapters removed because of a read error.
- Text recognition (`AndroidPdfPages`) renders each page at about 3 megapixels and reads it with
  ML Kit's bundled Latin-script recognizer (`com.google.mlkit:text-recognition`). The model ships in
  the APK, so it works offline, without Google Play services, and only changes with the dependency
  version. `OcrPageLayout` turns the recognized lines into page text: it puts them in reading order
  itself (the recognizer can list a running header after the line below it), reads a two-column
  page or two-page spread column by column when a clear gap separates them, joins pieces of one
  visual line, and starts a paragraph at a first-line indent (further right than the lines around
  it, so a block quote is not a paragraph per line) or at more space above a line than the page
  usually leaves (so a double-spaced page is not either). It never starts one next to the lines at
  a page's top and bottom, which may be a running header or page number; `PdfTextReflow` takes it
  from there. The minified build keeps ML Kit's component constructors (`proguard-rules.pro`).
- It runs while the novel is added (or synced), reporting "Recognizing text in "…": page N of M" on
  the Local folder screen. Like any import it goes on if you leave the screen; if the app is closed
  it stops at the next page, and adding the novel again resumes there. Each page's text is saved
  at once under `files/local_source_ocr/<content hash>/` (`PdfOcrCache`), so a scan is recognized
  once: rescans, syncs and chapter reads use the saved text, an interrupted import resumes at the
  next page, and chapter ids stay steady. A renamed or moved file keeps its text; an edited file is
  recognized again.
- Errors are `LocalSourceException`s, which the download queue classifies by type (never retried)
  rather than by their message, which names the user's files.

## Screen

Add story › **Local folder** (`AppRoute.LocalLibrary`, dev token `local`) shows the chosen folder,
each novel in it with Add (or "In your library", which opens it), and Add all. Imports run one at a
time and stay on the screen; a novel that fails is listed with its reason and Add all goes on with
the next. Leaving the screen ends an Add all run after the current novel. Scan again (app bar)
re-reads the folder. The folder picker is registered on every render under one key, so a pick that
completes after the process was recreated still arrives.

## Known limits

- Sync adds new chapters only: an edited file's already-read chapters keep their old text, and a
  renamed file (or a file that gains or loses chapters) reads as new chapters while the old ones stay
  as removed-from-source chapters.
- Story ids come from entry names only, so two folders with the same novel name are one story.
- Only BOM, UTF-8 and Windows-1252 TXT files decode correctly (no GBK or Shift-JIS detection).
- PDF paragraphs end at short lines that close a sentence; text laid out with full-width last lines
  runs together.
- Recognized text has the usual OCR mistakes (`rn`/`m`, `l`/`I`/`1`, lost italics) and reaches the
  reader and TTS as is. Only Latin-script text is recognized (no Chinese, Japanese or Korean model).
  A novel page takes well under a second, so a long scan takes minutes. A chapter read that finds
  a scan's saved text missing (data cleared) recognizes the whole book first, with no progress
  shown.
- Recognized text is kept as first saved: a later layout fix or ML Kit update does not redo it
  (that would change chapter ids), and there is no "recognize again" action. Backups do not include
  it: on a new phone or after clearing data, the next sync recognizes the book again, and an id can
  change if a heading reads differently. A text PDF recognized before
  Android 15 is read from its text layer after the upgrade, with the same risk.
- Saved recognized text is not deleted with its novel (a book's text is a few hundred KB). ML Kit
  sends Google anonymous usage statistics, never page images or text. A page whose text is two
  columns crossed by more than one wide line, or a mid-page line spanning both, can read out of order.
- Local covers are not deleted with their novel, and their `file://` path is absolute, so a restored
  backup shows the cover again after the next sync.

## QA

Push sample books to `/sdcard/Documents/Novels` and cold-start onto the screen with
`dev_start_screen local`. For a scanned PDF, render text pages to images and join them into a PDF
with no text layer (any image-to-PDF tool); `adb shell run-as <package> rm -r files/local_source_ocr`
forces recognition again. On the emulator, the system folder picker ignores instant synthetic taps:
use a short `longpress <x> <y> 150` to open folders and press "Use this folder".
