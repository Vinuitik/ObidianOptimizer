"""Multi-vault (VAULTS_PLAN M5): everything vault-scoped in the embedder follows the vault it
belongs to - two tmp vaults (id 1 at the legacy-style root, id 2 elsewhere), no DB, no network.

Rules under test:
  - backend-driven work carries vault_id + root and uses THEM, never "whichever is active"
    (an ingest that finishes after a switch writes into its own vault);
  - MCP / browser-facing GETs resolve the ACTIVE vault per call (cached ~5s);
  - every vault-scoped SQL names vault_id.
"""
import json
import time
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient

import mcp_server
import vault_paths
from vault_paths import Vault
from ingest import jobs as ingest_jobs
from ingest import placement, publish


@pytest.fixture
def two(tmp_path):
    """(vault1, vault2) with distinct notes."""
    v1 = Vault(1, tmp_path / "vault")
    v2 = Vault(2, tmp_path / "vaults" / "2")
    for v, tag in ((v1, "one"), (v2, "two")):
        (v.root / "AI").mkdir(parents=True)
        (v.root / "AI" / f"{tag}.md").write_text(f"# {tag}\n")
        (v.root / "only_here.md").write_text(f"note of vault {v.id}")
    return v1, v2


@pytest.fixture
def client(monkeypatch, tmp_path):
    import main as embedder_main
    monkeypatch.setattr(ingest_jobs, "BUNDLE_DIR", tmp_path / "bundles")
    return TestClient(embedder_main.app)


def _wait(job_id, statuses=("DONE", "FAILED", "DEFERRED"), timeout=5.0):
    end = time.time() + timeout
    while time.time() < end:
        j = ingest_jobs.get(job_id)
        if j and j["status"] in statuses:
            return j
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} did not settle")


# ── Vault value object ───────────────────────────────────────────────────────

def test_dirs_vault1_keeps_legacy_mounts_others_use_root(two, monkeypatch):
    v1, v2 = two
    for var in ("AGENT_REPORTS_DIR", "DOWNLOAD_DIR", "RESOURCE_DIR"):
        monkeypatch.delenv(var, raising=False)
    assert (v1.reports_dir(), v1.workspace_dir(), v1.resources_dir()) == \
        ("/reports", "/workspace", "/resources")
    assert v2.reports_dir() == str(v2.root / "_reports")
    assert v2.workspace_dir() == str(v2.root / "_workspace")
    assert v2.resources_dir() == str(v2.root / "resources")


def test_of_requires_root_for_non_default_vault():
    assert Vault.of(None, None).id == 1
    assert Vault.of(2, "/vaults/2") == Vault(2, Path("/vaults/2"))
    with pytest.raises(ValueError):
        Vault.of(2, None)           # never guess another vault's location


def test_resolve_refuses_paths_of_another_vault(two):
    v1, v2 = two
    assert v2.resolve("only_here.md").read_text() == "note of vault 2"
    with pytest.raises(ValueError):
        v2.resolve(str(v1.root / "only_here.md"))
    with pytest.raises(ValueError):
        v2.resolve("../vault/only_here.md")


def test_rel_strips_own_root_and_legacy_mounts(two):
    v1, v2 = two
    assert v2.rel(v2.root / "resources" / "media" / "a.mp4") == "resources/media/a.mp4"
    assert v1.rel("/resources/media/a.mp4") == "resources/media/a.mp4"
    assert v1.rel("/workspace/x.pdf") == "_workspace/x.pdf"
    assert v2.rel("/resources/media/a.mp4") == "/resources/media/a.mp4"   # legacy mounts are vault 1 only


# ── active vault lookup (MCP + browser-facing GETs) ──────────────────────────

def test_active_is_cached_then_refreshes_on_switch(monkeypatch):
    seq = [Vault(1, Path("/vault")), Vault(2, Path("/vaults/2"))]
    calls = []
    monkeypatch.setattr(vault_paths, "_fetch_active", lambda: calls.append(1) or seq[len(calls) - 1])
    now = [100.0]
    monkeypatch.setattr(vault_paths.time, "monotonic", lambda: now[0])
    vault_paths.invalidate_active()

    assert vault_paths.active().id == 1
    now[0] += 1
    assert vault_paths.active().id == 1 and len(calls) == 1       # inside the TTL: no DB hit
    now[0] += vault_paths.ACTIVE_TTL_S + 1
    assert vault_paths.active().id == 2 and len(calls) == 2       # switched vault seen after TTL
    vault_paths.invalidate_active()


def test_active_serves_stale_on_db_error_but_never_guesses(monkeypatch):
    now = [0.0]
    monkeypatch.setattr(vault_paths.time, "monotonic", lambda: now[0])
    vault_paths.invalidate_active()

    def down():
        raise RuntimeError("db down")

    monkeypatch.setattr(vault_paths, "_fetch_active", down)
    with pytest.raises(RuntimeError):                  # nothing cached: do NOT fall back to vault 1
        vault_paths.active()

    monkeypatch.setattr(vault_paths, "_fetch_active", lambda: Vault(2, Path("/vaults/2")))
    assert vault_paths.active().id == 2
    now[0] += 60
    monkeypatch.setattr(vault_paths, "_fetch_active", down)
    assert vault_paths.active().id == 2                # stale beats wrong
    vault_paths.invalidate_active()


def test_active_without_registry_is_vault1(monkeypatch):
    monkeypatch.setattr(vault_paths, "_fetch_active", lambda: None)
    vault_paths.invalidate_active()
    assert vault_paths.active().id == 1
    vault_paths.invalidate_active()


# ── MCP tools follow the active vault ────────────────────────────────────────

def test_mcp_file_tools_follow_active_vault(two, monkeypatch):
    v1, v2 = two
    cur = [v1]
    monkeypatch.setattr(mcp_server, "active_vault", lambda: cur[0])

    assert mcp_server.get_note_content("only_here.md") == "note of vault 1"
    assert {t["folder"] for t in mcp_server.get_vault_tree()} == {str(v1.root), f"{v1.root}/AI"}
    assert mcp_server.list_folder("AI", include_notes=True)["notes"] == ["one"]

    cur[0] = v2                                         # user switched vault
    assert mcp_server.get_note_content("only_here.md") == "note of vault 2"
    assert {t["folder"] for t in mcp_server.get_vault_tree()} == {str(v2.root), f"{v2.root}/AI"}
    assert mcp_server.list_folder("AI", include_notes=True)["notes"] == ["two"]
    with pytest.raises(ValueError):                     # the other vault's file is out of bounds
        mcp_server.get_note_content(str(v1.root / "only_here.md"))
    assert mcp_server.list_folder("")["folder"] == str(v2.root)


def test_mcp_search_scopes_both_rankers_by_vault(two, monkeypatch):
    v1, v2 = two
    seen = []
    monkeypatch.setattr(mcp_server, "_query_db",
                        lambda sql, params=(): seen.append((sql, params)) or [])
    monkeypatch.setattr("mcp_tools.search.embed_texts", lambda texts, kind="query": [[0.1, 0.2]])

    monkeypatch.setattr(mcp_server, "active_vault", lambda: v2)
    mcp_server.search_notes("adam optimizer")
    assert len(seen) == 2
    for sql, params in seen:
        assert "vault_id = %s" in sql
        assert 2 in params and 1 not in params

    seen.clear()
    mcp_server.search_in_vault("adam optimizer", 5, 1)  # the ingest linker names its job's vault
    assert all(1 in params for _, params in seen)


def test_find_home_filters_staging_by_active_root_and_scopes_db(two, monkeypatch):
    v1, v2 = two
    monkeypatch.setattr(mcp_server, "active_vault", lambda: v2)
    rows = [(f"{v2.root}/ingest/x.md", 0, "t", 0.9), (f"{v2.root}/AI/Real.md", 0, "t", 0.8)]
    seen = []
    monkeypatch.setattr(mcp_server, "_query_db",
                        lambda sql, params=(): seen.append(params) or list(rows))
    monkeypatch.setattr("mcp_tools.vault.embed_texts", lambda texts, kind="query": [[0.1, 0.2]])
    monkeypatch.setattr(placement, "suggest_folder", lambda text, vault=None: f"{vault.root}/AI")

    out = mcp_server.find_home_for_note("adam")
    assert out["suggested_folder"] == f"{v2.root}/AI"
    assert out["similar_notes"] == [f"{v2.root}/AI/Real.md"]      # <root>/ingest/ skipped
    assert 2 in seen[0]


# ── placement ────────────────────────────────────────────────────────────────

def test_placement_queries_by_vault_id_not_path_prefix(monkeypatch):
    seen = []
    monkeypatch.setattr(mcp_server, "_query_db",
                        lambda sql, params=(): seen.append((sql, params)) or [])
    placement._folder_rows(2)
    placement._note_vectors(["/vaults/2/a.md"], 2)
    for sql, params in seen:
        assert "vault_id = %s" in sql and "LIKE" not in sql
        assert 2 in params


def test_placement_tree_is_rooted_per_vault_and_cached_per_vault(monkeypatch):
    v = np.asarray
    def rows(vid):
        root = "/vault" if vid == 1 else "/vaults/2"
        return [(f"{root}/AI/Agents", v([1.0, 0, 0]), 10), (f"{root}/AI/ML", v([0, 1.0, 0]), 10),
                (f"{root}/Cooking/Soup", v([0, 0, 1.0]), 10), (f"{root}/_inbox", v([1.0, 0, 0]), 99)]
    monkeypatch.setattr(placement, "_folder_rows", rows)
    placement.invalidate_cache()
    v1, v2 = Vault(1, Path("/vault")), Vault(2, Path("/vaults/2"))
    vec = np.asarray([1.0, 0.0, 0.0], dtype=np.float32)

    assert placement.suggest(placement._model(v1), vec, "/vault") == "/vault/AI/Agents"
    assert placement.suggest(placement._model(v2), vec, "/vaults/2") == "/vaults/2/AI/Agents"
    assert placement._model(v1) is not placement._model(v2)
    placement.invalidate_cache()


def test_suggest_group_uses_the_given_vault(monkeypatch):
    got = {}
    monkeypatch.setattr(placement, "_note_vectors",
                        lambda paths, vault_id: got.setdefault("vid", vault_id) and
                        [np.asarray([1.0, 0.0, 0.0], dtype=np.float32)])
    monkeypatch.setattr(placement, "_folder_rows",
                        lambda vid: [("/vaults/2/AI/Agents", np.asarray([1.0, 0, 0]), 10),
                                     ("/vaults/2/Cooking/Soup", np.asarray([0, 1.0, 0]), 10)])
    placement.invalidate_cache()
    out = placement.suggest_group(["/vaults/2/_inbox/c/a.md"], Vault(2, Path("/vaults/2")))
    assert got["vid"] == 2
    assert out == "/vaults/2/AI/Agents"
    placement.invalidate_cache()


# ── ingest write path ────────────────────────────────────────────────────────

class _Resp:
    status_code = 200
    text = ""
    def __init__(self, body=None): self._b = body or {}
    def json(self): return self._b


def _capture_backend(monkeypatch):
    """Stub the Java internal API; return the list of (method, path, json) it received."""
    sent = []
    monkeypatch.setattr(publish, "INTERNAL_TOKEN", "tok")
    def post(url, headers=None, json=None, timeout=None, **kw):
        sent.append(("POST", url.split("/api/internal")[1], json))
        return _Resp({"path": "/x/n.md", "sourcePath": "_inbox/_sources/c.md", "relPath": "r"})
    def put(url, headers=None, json=None, timeout=None, **kw):
        sent.append(("PUT", url.split("/api/internal")[1], json))
        return _Resp()
    monkeypatch.setattr(publish.httpx, "post", post)
    monkeypatch.setattr(publish.httpx, "put", put)
    return sent


def test_standalone_publish_callbacks_carry_the_jobs_vault_after_a_switch(two, monkeypatch):
    from ingest import linking, synthesize
    v1, v2 = two
    sent = _capture_backend(monkeypatch)
    monkeypatch.setattr(synthesize, "_complete", lambda p, s: "Drafted body text.")
    monkeypatch.setattr(linking, "related_links", lambda *a, **k: [])
    monkeypatch.setattr(publish, "find_home", lambda t, vault=None: "Study")
    monkeypatch.setattr(publish, "validate_note", lambda md, names: [])
    # the user switched to vault 1 while this vault-2 job was still running:
    monkeypatch.setattr(mcp_server, "active_vault", lambda: v1)

    from tests.test_cutover_v2 import _text_bundle
    job = {"_vault": v2, "vault_id": 2, "capture_id": "cap"}
    ingest_jobs._synthesize_and_publish_v2(job, _text_bundle())

    assert job["notes_created"]
    assert {p for _, p, _ in sent} == {"/folders", "/notes"}
    assert all(body["vaultId"] == 2 for _, _, body in sent), sent


def test_inplace_job_reads_and_writes_its_own_vault(two, monkeypatch):
    from ingest import synthesize
    v1, v2 = two
    sent = _capture_backend(monkeypatch)
    (v2.root / "lec.md").write_text("# Lecture\n\n![[resources/audio/x.mp3]]\n")
    (v1.root / "lec.md").write_text("WRONG VAULT")
    monkeypatch.setattr(mcp_server, "active_vault", lambda: v1)         # switched away
    monkeypatch.setattr(ingest_jobs, "_run_pipeline_v2",
                        lambda job, bundle: type("R", (), {"notes": []})())
    monkeypatch.setattr(synthesize, "build_inplace_body_v2", lambda *a, **k: "BLOCK")

    job = {"_vault": v2, "vault_id": 2, "note_path": "lec.md",
           "embed_ref": "resources/audio/x.mp3", "_resolved_path": None}
    ingest_jobs._synthesize_and_inject_v2(job, {"source": {"type": "audio"}, "media": []})

    assert ("POST", "/capture") in {(m, p) for m, p, _ in sent}
    capture = next(b for m, p, b in sent if p == "/capture")
    assert capture["vaultId"] == 2 and "# Lecture" in capture["content"]   # read from vault 2's disk
    put = next(b for m, p, b in sent if m == "PUT")
    assert put["vaultId"] == 2 and "BLOCK" in put["content"]


def test_ingest_endpoint_builds_the_job_vault_from_the_request(two, client, monkeypatch):
    v1, v2 = two
    (v2.root / "resources" / "videos").mkdir(parents=True)
    (v2.root / "resources" / "videos" / "clip.mp4").write_bytes(b"x")
    monkeypatch.setattr(mcp_server, "active_vault", lambda: v1)         # NOT the request's vault
    seen = {}
    from ingest import extract_av
    monkeypatch.setattr(extract_av, "extract",
                        lambda ref, resolved, force_whisper=False: seen.update(resolved=str(resolved))
                        or {"source": {"type": "video", "ref": ref, "title": "t",
                                       "duration_s": 1.0, "chapters": []},
                            "segments": [], "media": []})

    res = client.post("/ingest", json={"ref": "resources/videos/clip.mp4", "extract_only": True,
                                       "vault_id": 2, "root": str(v2.root)})
    assert res.status_code == 200 and res.json()["vault_id"] == 2
    assert _wait(res.json()["id"])["status"] == "DONE"
    assert seen["resolved"] == str(v2.root / "resources" / "videos" / "clip.mp4")

    # not in the request's vault -> 404 (vault 1 has no such file either, but never searched)
    (v1.root / "resources").mkdir()
    (v1.root / "resources" / "only1.mp4").write_bytes(b"x")
    res = client.post("/ingest", json={"ref": "resources/only1.mp4", "extract_only": True,
                                       "vault_id": 2, "root": str(v2.root)})
    assert res.status_code == 404


def test_ingest_endpoint_422_when_non_default_vault_has_no_root(client):
    res = client.post("/ingest", json={"ref": "x.mp4", "vault_id": 2})
    assert res.status_code == 422


def test_inplace_resume_index_is_namespaced_per_vault(tmp_path, monkeypatch):
    from ingest import inplace_resume
    monkeypatch.setattr(ingest_jobs, "BUNDLE_DIR", tmp_path / "b")
    (tmp_path / "b").mkdir()
    bundle = tmp_path / "b" / "x.json"
    bundle.write_text("{}")
    inplace_resume.record("n.md", "e.mp4", str(bundle), vault_id=2)
    assert inplace_resume.lookup("n.md", "e.mp4", 2) is not None
    assert inplace_resume.lookup("n.md", "e.mp4", 1) is None          # same relative path, other vault
    inplace_resume.drop("n.md", "e.mp4", 2)
    assert inplace_resume.lookup("n.md", "e.mp4", 2) is None


def test_prefetch_and_local_copy_go_to_the_jobs_vault_resources(two, monkeypatch):
    v1, v2 = two
    dest = {}
    from download import downloader
    monkeypatch.setattr(downloader, "download_sync",
                        lambda url, dest_dir=None: dest.update(d=dest_dir) or
                        str(Path(dest_dir) / "v.mp4"))
    monkeypatch.setattr(ingest_jobs, "_attach_keyframes", lambda *a, **k: None)
    bundle = {"source": {}, "media": [{"x": 1}]}
    job = {"ref": "https://youtu.be/abc", "_vault": v2, "vault_id": 2}
    ingest_jobs._ensure_local_copy(job, bundle, None)
    assert dest["d"] == str(v2.root / "resources" / "media")
    assert bundle["source"]["local"] == "resources/media/v.mp4"


# ── downloads, cards, reports, failures ──────────────────────────────────────

def test_download_job_for_other_vault_targets_its_workspace(two, monkeypatch):
    from download import downloader
    from download import jobs as download_jobs
    v1, v2 = two
    got = {}
    monkeypatch.setattr(downloader, "download_sync",
                        lambda url, hook=None, dest_dir=None: got.update(dest=dest_dir) or "f.mp4")
    job = download_jobs.submit("https://youtu.be/x", v2)
    end = time.time() + 5
    while time.time() < end and download_jobs.get(job["id"])["status"] != "done":
        time.sleep(0.02)
    assert got["dest"] == str(v2.root / "_workspace")


def test_flashcards_endpoint_reads_note_from_requested_vault(two, client, monkeypatch):
    from flashcards import generate as card_gen
    v1, v2 = two
    monkeypatch.setattr(mcp_server, "active_vault", lambda: v1)
    got = {}
    monkeypatch.setattr(card_gen, "generate_for_note",
                        lambda note_path, content, sh, target_count=None, feedback=None, vault=None:
                        got.update(content=content, vault=vault) or {"stored": 0})
    res = client.post("/flashcards/generate", json={"note_path": "only_here.md",
                                                    "vault_id": 2, "root": str(v2.root)})
    assert res.status_code == 200
    assert got["content"] == "note of vault 2" and got["vault"] == v2


def test_cards_and_image_lookup_sql_carry_vault_id(monkeypatch):
    from flashcards import generate as gen
    executed = []

    class FakeConn:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def execute(self, sql, params=None):
            executed.append((sql, params))
            return type("Cur", (), {"rowcount": 1, "fetchall": lambda s: []})()
        def commit(self): pass

    monkeypatch.setattr(gen.psycopg, "connect", lambda url: FakeConn())
    card = {"type": "open", "difficulty": 2, "question": "q",
            "reference_answers": ["a", "b"], "explanation": "e"}
    gen._store("/vaults/2/a.md", "sh", [card], 2)
    sql, params = next((s, p) for s, p in executed if "INSERT INTO cards" in s)
    assert "vault_id" in sql and params[-1] == 2

    executed.clear()
    gen._with_image_descriptions("/vaults/2/a.md", "body", 2)
    sql, params = executed[0]
    assert "vault_id = %s" in sql and params == ("/vaults/2/a.md", 2)


def test_agent_reports_land_in_the_vaults_reports_dir(two, tmp_path):
    from agent_reports import AgentReport
    v1, v2 = two
    rep = AgentReport("flashcards", "a.md", reports_dir=v2.reports_dir())
    rep.input("x", "y").save()
    assert list((v2.root / "_reports" / "flashcards").glob("*.md"))


def test_failure_ledger_records_the_vault(monkeypatch):
    import failures
    executed = []

    class FakeConn:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def execute(self, sql, params=None): executed.append((sql, params))
        def commit(self): pass

    monkeypatch.setattr(failures.psycopg, "connect", lambda url: FakeConn())
    failures.record_failure("ingest", "synthesize", {"ref": "x"}, RuntimeError("boom"), vault_id=2)
    sql, params = next((s, p) for s, p in executed if "INSERT INTO pipeline_failures" in s)
    assert "vault_id" in sql and params[-1] == 2


def test_minicourse_reads_items_from_its_vault(two):
    from tracks import minicourse
    v1, v2 = two
    items = [{"title": "T", "notePath": "only_here.md"}]
    b2 = minicourse.build_course_bundle("t", "Track", items, v2)
    b1 = minicourse.build_course_bundle("t", "Track", items, v1)
    assert b2["segments"][0]["text"] == "note of vault 2"
    assert b1["segments"][0]["text"] == "note of vault 1"


def test_publish_payloads_carry_vault_id(monkeypatch):
    sent = _capture_backend(monkeypatch)
    publish.create_note("f", "t", "c", capture_id="cap", vault_id=3)
    publish.update_note("n.md", "c", 3)
    publish.ensure_folder("f", 3)
    publish.store_media("a.png", "AAEC", 3)
    publish.create_track("T", "custom", 3)
    publish.add_track_item("1", "t", "n.md", vault_id=3)
    publish.create_capture("c", "s", "x", 3)
    assert len(sent) == 7 and all(b["vaultId"] == 3 for _, _, b in sent)

    seen = {}
    monkeypatch.setattr(publish.httpx, "get",
                        lambda url, params=None, headers=None, timeout=None:
                        seen.update(params=params) or _Resp({"title": "T", "items": []}))
    publish.get_track_items("1", 3)
    assert seen["params"] == {"vaultId": 3}
