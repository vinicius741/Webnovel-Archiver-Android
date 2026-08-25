"""Block parsing, sanitization, and protected-block classification for the chapter-polish spike.

Pure stdlib, no network. Mirrors the app-side conventions the plan builds on:

- Input sanitization keeps only the prose/structural tags the reader allows
  (ChapterHtmlSitizer's relaxed safelist, minus attributes we do not need:
  class/style junk from Royal Road is dropped before classification).
- A chapter becomes an ordered list of *blocks* (top-level elements). Every
  block gets a stable id (``b0001``...). The model must return every input
  block id exactly once, in order.
- Protected blocks (System panels, tables, dividers, headings, spacers,
  stat-like paragraphs) must be copied byte-for-byte after whitespace
  normalization; everything else is addressable prose.

Classification is deliberately conservative: a false positive leaves awkward
prose untouched, a false negative may change game rules.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field

# Tags kept when sanitizing chapter HTML for the pipeline. Everything else is
# unwrapped (children kept, tag and attributes dropped).
BLOCK_TAGS = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li",
              "table", "thead", "tbody", "tr", "th", "td", "hr"}
INLINE_TAGS = {"br", "strong", "b", "em", "i", "u", "sup", "sub"}
VOID_TAGS = {"br", "hr"}

# The prose-only output allowlist the model must obey for addressable blocks.
# Anything else in a rewritten block is stripped (and recorded) by validation.
PROSE_OUTPUT_TAGS = {"p", "br", "strong", "em", "blockquote"}


@dataclass
class Block:
    id: str
    tag: str
    html: str  # sanitized, self-contained
    protected: bool
    reason: str  # classification reason, for the run report
    protected_hash: str = field(default="")

    @property
    def text(self) -> str:
        return text_of(self.html)


def text_of(html: str) -> str:
    """Visible text of an HTML fragment (tags stripped, entities decoded later by caller if needed)."""
    out: list[str] = []
    stack: list[str] = []
    for m in re.finditer(r"<[^>]+>|[^<]+", html):
        tok = m.group(0)
        if tok.startswith("<"):
            if tok.startswith("</"):
                if stack:
                    stack.pop()
            elif not tok.endswith("/>"):
                tag = tok[1:].split()[0].lower()
                if tag not in VOID_TAGS:
                    stack.append(tag)
                if tag == "br":
                    out.append("\n")
        else:
            if any(t in ("script", "style") for t in stack):
                continue
            out.append(tok)
    return unescape("".join(out))


def unescape(s: str) -> str:
    return (s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", '"').replace("&#39;", "'").replace("&nbsp;", " ")
             .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
             .replace("&rsquo;", "’").replace("&lsquo;", "‘").replace("&rdquo;", "”").replace("&ldquo;", "“")
             .replace("&apos;", "'"))


class _Rebuilder:
    """html.parser visitor that rebuilds a fragment using only allowed tags, no attributes."""

    def __init__(self, keep: set[str]) -> None:
        self.keep = keep
        self.out: list[str] = []
        self.open: list[str] = []
        self.skip_depth = 0

    def handle_starttag(self, tag, attrs) -> None:
        tag = tag.lower()
        if tag in ("script", "style"):
            self.skip_depth += 1
            return
        if tag in self.keep:
            if tag in VOID_TAGS:
                self.out.append(f"<{tag}>")
            else:
                self.out.append(f"<{tag}>")
                self.open.append(tag)

    def handle_startendtag(self, tag, attrs) -> None:
        tag = tag.lower()
        if tag == "br":
            self.out.append("<br>")
        elif tag == "hr" and "hr" in self.keep:
            self.out.append("<hr>")

    def handle_endtag(self, tag) -> None:
        tag = tag.lower()
        if tag in ("script", "style"):
            self.skip_depth = max(0, self.skip_depth - 1)
            return
        if tag in self.open:
            # close any implicitly-unclosed inner tags first
            while self.open:
                top = self.open.pop()
                self.out.append(f"</{top}>")
                if top == tag:
                    break

    def handle_data(self, data) -> None:
        if self.skip_depth == 0:
            self.out.append(data)


def sanitize_chapter_html(html: str) -> str:
    """Sanitize downloaded chapter HTML to the pipeline's input allowlist."""
    from html.parser import HTMLParser

    class P(HTMLParser):
        def __init__(self) -> None:
            super().__init__(convert_charrefs=True)
            self.r = _Rebuilder(BLOCK_TAGS | INLINE_TAGS)

        def handle_starttag(self, tag, attrs):
            self.r.handle_starttag(tag, attrs)

        def handle_startendtag(self, tag, attrs):
            self.r.handle_startendtag(tag, attrs)

        def handle_endtag(self, tag):
            self.r.handle_endtag(tag)

        def handle_data(self, data):
            self.r.handle_data(data)

    p = P()
    p.feed(html)
    p.close()
    # close anything left open
    while p.r.open:
        p.r.out.append(f"</{p.r.open.pop()}>")
    return "".join(p.r.out)


@dataclass
class ParsedChapter:
    blocks: list[Block]
    source_sha256: str  # over the sanitized html

    @property
    def addressable(self) -> list[Block]:
        return [b for b in self.blocks if not b.protected]

    def html_of(self, blocks: list[Block] | None = None) -> str:
        return "\n".join(b.html for b in (blocks if blocks is not None else self.blocks))


_STAT_LINE = re.compile(
    r"^\s*(\[.*\]|[A-Z][A-Za-z /_]{1,24}\s*:|[-–—=*•]{3,}|\d[\d.,/%\s]*(\s*(HP|MP|SP|XP|DMG|STR|INT|VIT|LV|LVL))?\b.*)$"
)
_NUMERIC_LINE = re.compile(r"^[\s\d.,:%/()+\-–—xX×\[\]|#*]+$")


def _looks_like_system_text(text: str) -> bool:
    lines = [ln for ln in text.splitlines() if ln.strip()]
    if not lines:
        return False
    statish = 0
    for ln in lines:
        s = ln.strip()
        if _NUMERIC_LINE.match(s) or _STAT_LINE.match(s) or (s.startswith("[") and s.endswith("]")):
            statish += 1
    return statish / len(lines) >= 0.5


def _strong_share(html: str) -> float:
    strongs = re.findall(r"<(?:strong|b)>(.*?)</(?:strong|b)>", html, re.S | re.I)
    if not strongs:
        return 0.0
    strong_chars = sum(len(text_of(s)) for s in strongs)
    total = len(text_of(html).strip())
    return strong_chars / total if total else 0.0


def _is_spacer(html: str, text: str) -> bool:
    return not re.sub(r"\s+", "", text).strip()


def classify_block(tag: str, html: str, text: str) -> tuple[bool, str]:
    """Returns (protected, reason). Conservative: when unsure, protect."""
    if tag == "hr":
        return True, "divider"
    if tag in {"table", "ul", "ol"}:
        return True, f"interface block ({tag})"
    if tag in {"h1", "h2", "h3", "h4", "h5", "h6"}:
        return True, "heading"
    if tag == "blockquote":
        return True, "blockquote (System panel convention)"
    if _is_spacer(html, text):
        return True, "spacer"
    if _looks_like_system_text(text):
        return True, "stat/system-like text"
    if len(text.splitlines()) >= 3 and _strong_share(html) >= 0.8:
        return True, "bold multi-line panel"
    return False, "prose"


def normalize_for_compare(html: str) -> str:
    """Canonical form for protected-block byte-for-byte comparison after normalization."""
    s = re.sub(r"\s+", " ", html)
    s = re.sub(r">\s+<", "><", s)
    return s.strip().lower()


def protected_hash(html: str) -> str:
    return hashlib.sha256(normalize_for_compare(html).encode("utf-8")).hexdigest()


def parse_chapter(html: str) -> ParsedChapter:
    """Sanitize + split chapter HTML into ordered, classified, id-stamped blocks."""
    sanitized = sanitize_chapter_html(html)
    blocks: list[Block] = []
    # Walk top-level nodes of the sanitized fragment.
    pos = 0
    pattern = re.compile(r"<(p|h[1-6]|blockquote|ul|ol|table|hr)\b[^>]*>", re.I)
    buf: list[str] = []
    while pos < len(sanitized):
        m = pattern.search(sanitized, pos)
        if not m:
            buf.append(sanitized[pos:])
            break
        if m.start() > pos:
            buf.append(sanitized[pos:m.start()])
        tag = m.group(1).lower()
        if tag == "hr":
            blocks.append(_make_block("hr", "<hr>", len(blocks)))
            pos = m.end()
            continue
        # find the matching close tag (no nesting of same tag expected at top level for p/h;
        # blockquote/table/ul can nest — find balanced end)
        end = _find_balanced_end(sanitized, m.end(), tag)
        inner = sanitized[m.end():end]
        blocks.append(_make_block(tag, f"<{tag}>{inner}</{tag}>", len(blocks)))
        pos = end + len(f"</{tag}>")
    stray = "".join(buf).strip()
    if stray:
        # Loose top-level text/images outside recognized blocks: keep as a protected
        # pre-block so it is returned unchanged rather than dropped.
        blocks.insert(0, Block(id="b0000", tag="pre", html=stray, protected=True, reason="loose content outside block tags"))
        for i, b in enumerate(blocks):
            b.id = f"b{i:04d}"
    for b in blocks:
        if b.protected:
            b.protected_hash = protected_hash(b.html)
    digest = hashlib.sha256(re.sub(r"\s+", " ", sanitized).strip().encode("utf-8")).hexdigest()
    return ParsedChapter(blocks=blocks, source_sha256=digest)


def _find_balanced_end(s: str, start: int, tag: str) -> int:
    depth = 1
    open_re = re.compile(rf"<{tag}\b[^>]*>", re.I)
    close_re = re.compile(rf"</{tag}\s*>", re.I)
    pos = start
    while pos < len(s):
        c = close_re.search(s, pos)
        if not c:
            return len(s) - len(f"</{tag}>")
        o = open_re.search(s, pos, c.start())
        if o and o.start() < c.start():
            depth += 1
            pos = o.end()
            continue
        depth -= 1
        if depth == 0:
            return c.start()
        pos = c.end()
    return len(s) - len(f"</{tag}>")


def _make_block(tag: str, html: str, index: int) -> Block:
    text = text_of(html)
    protected, reason = classify_block(tag, html, text)
    b = Block(id=f"b{index + 1:04d}", tag=tag, html=html, protected=protected, reason=reason)
    if protected:
        b.protected_hash = protected_hash(html)
    return b


def assemble_chapter_html(blocks: list[Block]) -> str:
    return "\n".join(b.html for b in blocks)


# ---------------------------------------------------------------- validations

@dataclass
class ValidationIssue:
    code: str
    detail: str


def sanitize_output_block(html: str) -> tuple[str, list[str]]:
    """Strip anything outside the prose output allowlist. Returns (clean, notes)."""
    from html.parser import HTMLParser

    notes: list[str] = []
    keep = PROSE_OUTPUT_TAGS

    class P(HTMLParser):
        def __init__(self) -> None:
            super().__init__(convert_charrefs=False)
            self.r = _Rebuilder(keep)

        def handle_starttag(self, tag, attrs):
            if tag in keep:
                self.r.handle_starttag(tag, attrs)
            else:
                if tag in ("script", "style", "iframe", "object", "embed", "img", "a"):
                    notes.append(f"removed <{tag}>")
                self.r.handle_data("")

        def handle_startendtag(self, tag, attrs):
            self.r.handle_startendtag(tag, attrs)

        def handle_endtag(self, tag):
            self.r.handle_endtag(tag)

        def handle_data(self, data):
            self.r.handle_data(data)

    p = P()
    p.feed(html)
    p.close()
    while p.r.open:
        p.r.out.append(f"</{p.r.open.pop()}>")
    return "".join(p.r.out), notes
