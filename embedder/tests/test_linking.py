"""Semantic linking tests (INGESTION_V2_FLOWS §5).

Pure: `embed_fn`/`candidates_fn` are injected, so no DB / embedder. Covers prose
extraction (strip media/comments/scaffolding), ANN aggregation + floor/exclude/cap,
best-effort failure (no links, never raises), and the `## Related` block shape.
"""
from ingest.linking import related_block, related_links, strip_for_query


def _embed(texts):
    return [[1.0, 0.0] for _ in texts]


def test_strip_for_query_removes_media_comments_scaffold():
    body = ("Real prose here.\n\n![[diagram.png]]\n\n<!-- ingest:x sha=1 -->\n\n"
            "## Source\nhttp://x\n\n## Sequence\n[[a]]\n\n## Related\n[[b]]")
    s = strip_for_query(body)
    assert "Real prose here." in s
    assert "diagram.png" not in s and "ingest:x" not in s
    assert "## Source" not in s and "Sequence" not in s and "Related" not in s


def test_related_links_filters_excludes_and_sorts():
    rows = [("A/foo.md", 0, "t", 0.90),
            ("B/bar.md", 1, "t", 0.60),
            ("C/low.md", 0, "t", 0.20),      # below floor → dropped
            ("D/self.md", 0, "t", 0.80)]     # excluded by stem
    out = related_links("prose", exclude_stems=["self"], embed_fn=_embed,
                        candidates_fn=lambda v: rows, floor=0.45, cap=5)
    assert out == ["foo", "bar"]             # sorted by similarity, floor + self applied


def test_related_links_caps_and_dedupes_by_note():
    rows = [(f"F/n{i}.md", 0, "t", 0.9 - i * 0.05) for i in range(10)]
    out = related_links("prose", embed_fn=_embed, candidates_fn=lambda v: rows,
                        floor=0.0, cap=3)
    assert out == ["n0", "n1", "n2"]


def test_related_links_aggregates_max_across_chunks():
    # two chunks return the same note at different sims → keep the max
    calls = {"n": 0}

    def cand(vec):
        calls["n"] += 1
        return [("A/foo.md", 0, "t", 0.5 if calls["n"] == 1 else 0.8)]

    long = "\n\n".join(["para " * 200 for _ in range(3)])   # forces >1 chunk
    out = related_links(long, embed_fn=_embed, candidates_fn=cand, floor=0.7, cap=5)
    assert out == ["foo"] and calls["n"] > 1                # survived on the 0.8 chunk


def test_related_links_best_effort_on_failure():
    def boom(texts):
        raise RuntimeError("index cold")
    assert related_links("prose", embed_fn=boom, candidates_fn=lambda v: []) == []


def test_related_links_tolerates_missing_similarity_column():
    out = related_links("prose", embed_fn=_embed,
                        candidates_fn=lambda v: [("A/x.md", 0, "t")], floor=0.0)
    assert out == ["x"]


def test_related_block_shape():
    assert related_block([]) == ""
    block = related_block(["foo", "bar"])
    assert block.startswith("## Related") and "[[foo]]" in block and "[[bar]]" in block
