"""Pure tests for the hierarchical centroid folder classifier (no DB, no embedder)."""
import numpy as np
import pytest

from ingest import placement


def _rows(spec):
    """spec: {folder: (vec_list, n)} → the (folder, ndarray, n) rows build_model wants."""
    return [(f, np.asarray(v, dtype=np.float32), n) for f, (v, n) in spec.items()]


# A tiny 3-dim "embedding" space: AI notes ≈ x-axis, Programming ≈ y-axis, Books ≈ z-axis.
BASE = {
    "/vault/AI/Agents":     ([1.0, 0.0, 0.0], 20),
    "/vault/AI/NLP":        ([0.9, 0.2, 0.0], 20),
    "/vault/Programming/Cloud": ([0.0, 1.0, 0.0], 20),
    "/vault/Books/Fiction": ([0.0, 0.0, 1.0], 20),
}


def _model(spec=BASE):
    return placement.build_model(_rows(spec))


def test_descends_to_the_closest_leaf():
    m = _model()
    got = placement.suggest(m, np.asarray([1.0, 0.05, 0.0], dtype=np.float32))
    assert got == "/vault/AI/Agents"


def test_never_suggests_a_bare_main_folder_min_depth_2():
    # A note pulled toward AI overall but not any specific child still must land at depth ≥2.
    m = _model()
    got = placement.suggest(m, np.asarray([0.95, 0.1, 0.0], dtype=np.float32))
    assert got and got.count("/") >= 3            # /vault/AI/<sub> — never just /vault/AI


def test_low_confidence_returns_unsorted(monkeypatch):
    m = _model()
    # Orthogonal-ish to everything → below MIN_SIM → None.
    monkeypatch.setattr(placement, "MIN_SIM", 0.9)
    assert placement.suggest(m, np.asarray([0.4, 0.4, 0.4], dtype=np.float32)) is None


def test_tiny_folders_are_skipped(monkeypatch):
    spec = dict(BASE)
    spec["/vault/AI/Fringe"] = ([1.0, 0.0, 0.0], 1)   # closest but only 1 note
    monkeypatch.setattr(placement, "MIN_FOLDER_NOTES", 3)
    got = placement.suggest(_model(spec), np.asarray([1.0, 0.0, 0.0], dtype=np.float32))
    assert got == "/vault/AI/Agents"                  # not the 1-note Fringe folder


def test_staging_and_media_folders_excluded_from_candidates():
    spec = dict(BASE)
    spec["/vault/_inbox"] = ([1.0, 0.0, 0.0], 500)    # huge, closest — must be ignored
    spec["/vault/resources/videos"] = ([1.0, 0.0, 0.0], 500)
    got = placement.suggest(_model(spec), np.asarray([1.0, 0.0, 0.0], dtype=np.float32))
    assert got == "/vault/AI/Agents"


def test_subtree_centroid_is_count_weighted():
    m = _model()
    ai = m["/vault/AI"]
    assert ai.sub_n == 40 and ai.sub_c is not None    # rolls up both AI children
