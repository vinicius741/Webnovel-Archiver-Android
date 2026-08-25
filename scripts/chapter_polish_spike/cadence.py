"""Deterministic cadence metrics over parsed chapter blocks (plan section 05, "The cadence report").

Style change gets a countable report so it can run through the pipeline, the
comparison view, and the release gates without any model calls. All functions
here are pure and unit-testable; they operate on *addressable prose blocks*
(protected System panels and spacers are excluded because their shape is
authorial interface content, not prose rhythm).

Reference numbers: the plan measured Foxkin of the Night Sky chapter 1 as
187 paragraph nodes, 91 paragraphs of five words or fewer, and 11 clear
clusters of three or more clipped fragments. ``selftest`` checks a fresh
parse against those numbers.
"""

from __future__ import annotations

import re
import statistics
from dataclasses import asdict, dataclass, field

from blocks import Block, text_of

FRAGMENT_MAX_WORDS = 5
CLUSTER_MIN_RUN = 3
TRIPLET_MAX_WORDS = 5
DOMINANT_SHARE_WARN = 0.55

_ABBREV = re.compile(r"\b(?:Mr|Mrs|Ms|Dr|St|Sr|Jr|vs|etc|Inc|Ltd|Prof)\.$")
_SENT_SPLIT = re.compile(r"(?<=[.!?…])[\"'”’)\]]*\s+")
_WS = re.compile(r"\s+")


def split_sentences(text: str) -> list[str]:
    """Sentence segmentation good enough for length statistics (not for display)."""
    text = _WS.sub(" ", text).strip()
    if not text:
        return []
    parts = _SENT_SPLIT.split(text)
    out: list[str] = []
    for p in parts:
        if not p:
            continue
        if out and _ABBREV.search(out[-1]):
            out[-1] = out[-1] + " " + p
        else:
            out.append(p)
    return out


def words(text: str) -> list[str]:
    return [w for w in re.split(r"\s+", text.strip()) if w]


@dataclass
class CadenceReport:
    paragraph_count: int
    word_count: int
    sentence_count: int
    fragment_paragraphs: int
    fragment_share: float
    cluster_count: int
    cluster_paragraphs: int
    triplet_count: int
    triplet_rate_per_100_paragraphs: float
    sentence_length_mean: float
    sentence_length_stdev: float
    sentence_length_cv: float
    em_dash_density_per_1000_words: float
    length_bucket_shares: dict[str, float] = field(default_factory=dict)
    dominant_bucket: str = ""
    dominant_bucket_share: float = 0.0

    def to_dict(self) -> dict:
        return asdict(self)


BUCKETS = [("1-5", 1, 5), ("6-12", 6, 12), ("13-20", 13, 20), ("21-35", 21, 35), ("36+", 36, 10**9)]


def cadence_of(blocks: list[Block]) -> CadenceReport:
    prose = [b for b in blocks if not b.protected]
    texts = [text_of(b.html) for b in prose]
    para_word_counts = [len(words(t)) for t in texts]
    all_text = " ".join(texts)

    sentence_lengths: list[int] = []
    triplet_count = 0
    for t in texts:
        sents = split_sentences(t)
        lens = [len(words(s)) for s in sents]
        sentence_lengths.extend(lens)
        for i in range(len(lens) - 2):
            if all(l <= TRIPLET_MAX_WORDS for l in lens[i:i + 3]):
                triplet_count += 1

    frag_flags = [c <= FRAGMENT_MAX_WORDS and c > 0 for c in para_word_counts]
    cluster_count = 0
    cluster_paragraphs = 0
    run = 0
    for f in frag_flags + [False]:  # sentinel closes the last run
        if f:
            run += 1
        else:
            if run >= CLUSTER_MIN_RUN:
                cluster_count += 1
                cluster_paragraphs += run
            run = 0

    total_words = sum(para_word_counts)
    sentence_mean = statistics.mean(sentence_lengths) if sentence_lengths else 0.0
    sentence_stdev = statistics.stdev(sentence_lengths) if len(sentence_lengths) > 1 else 0.0

    bucket_counts = {name: 0 for name, _, _ in BUCKETS}
    for l in sentence_lengths:
        for name, lo, hi in BUCKETS:
            if lo <= l <= hi:
                bucket_counts[name] += 1
                break
    bucket_shares = {name: (c / len(sentence_lengths)) if sentence_lengths else 0.0
                     for name, c in bucket_counts.items()}
    dominant = max(bucket_shares, key=lambda k: bucket_shares[k]) if bucket_shares else ""

    return CadenceReport(
        paragraph_count=len(prose),
        word_count=total_words,
        sentence_count=len(sentence_lengths),
        fragment_paragraphs=sum(frag_flags),
        fragment_share=(sum(frag_flags) / len(frag_flags)) if frag_flags else 0.0,
        cluster_count=cluster_count,
        cluster_paragraphs=cluster_paragraphs,
        triplet_count=triplet_count,
        triplet_rate_per_100_paragraphs=(100.0 * triplet_count / len(texts)) if texts else 0.0,
        sentence_length_mean=round(sentence_mean, 2),
        sentence_length_stdev=round(sentence_stdev, 2),
        sentence_length_cv=round(sentence_stdev / sentence_mean, 3) if sentence_mean else 0.0,
        em_dash_density_per_1000_words=round(1000.0 * all_text.count("—") / total_words, 2) if total_words else 0.0,
        length_bucket_shares={k: round(v, 4) for k, v in bucket_shares.items()},
        dominant_bucket=dominant,
        dominant_bucket_share=round(bucket_shares.get(dominant, 0.0), 4) if dominant else 0.0,
    )


@dataclass
class CadenceComparison:
    before: dict
    after: dict
    fragment_share_delta: float
    cluster_delta: int
    triplet_delta: int
    cv_delta: float
    em_dash_delta: float
    template_swap_warning: bool
    template_swap_detail: str

    def to_dict(self) -> dict:
        return asdict(self)


def compare(before: CadenceReport, after: CadenceReport) -> CadenceComparison:
    frag_delta = round(after.fragment_share - before.fragment_share, 4)
    cluster_delta = after.cluster_count - before.cluster_count
    triplet_delta = after.triplet_count - before.triplet_count
    cv_delta = round(after.sentence_length_cv - before.sentence_length_cv, 3)
    em_dash_delta = round(
        after.em_dash_density_per_1000_words - before.em_dash_density_per_1000_words, 2)

    clusters_reduced = cluster_delta <= -max(1, int(round(before.cluster_count * 0.3)))
    fragments_reduced = frag_delta <= -0.10
    dominant_grew = after.dominant_bucket_share >= max(
        DOMINANT_SHARE_WARN, before.dominant_bucket_share + 0.05)
    template_swap = (clusters_reduced or fragments_reduced) and dominant_grew
    detail = ""
    if template_swap:
        detail = (
            f"fragment clusters {before.cluster_count}->{after.cluster_count} but a single "
            f"sentence-length bucket ({after.dominant_bucket}) now holds "
            f"{after.dominant_bucket_share:.0%} of sentences (was "
            f"{before.dominant_bucket_share:.0%} in {before.dominant_bucket}): the rewrite "
            "likely swapped one template rhythm for another instead of varying rhythm."
        )
    return CadenceComparison(
        before=before.to_dict(),
        after=after.to_dict(),
        fragment_share_delta=frag_delta,
        cluster_delta=cluster_delta,
        triplet_delta=triplet_delta,
        cv_delta=cv_delta,
        em_dash_delta=em_dash_delta,
        template_swap_warning=template_swap,
        template_swap_detail=detail,
    )
