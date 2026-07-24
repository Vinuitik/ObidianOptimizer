#!/usr/bin/env python3
"""Live ingestion E2E — drives the REAL running stack (GPU embedder, host-wrapper,
nginx-less loopback backend) through the ingestion pipeline with the fast.ai
lesson-4 page and its embedded YouTube video.

Run:  ./linux_scripts/test.sh ingest-live      (or:  python3 ingest_live_e2e.py)

Ports (loopback, published by docker-compose):
  backend  127.0.0.1:8084   (nginx strips /api → paths here have NO /api prefix)
  embedder 127.0.0.1:8000   (/health, /ingest, /download — loopback-only, no auth)
  wrapper  127.0.0.1:5500   (/providers — the LLM gateway)

What it verifies, phase by phase (each PASS / FAIL / SKIP with timing):
  1 preflight        health + provider snapshot + backend login
  2 video-captions   yt-dlp caption fast-path (extract_only, no download, no LLM)
  3 page-web         trafilatura web extraction of the lesson page (no LLM)
  4 ytdlp-download    yt-dlp FULL download proxy → _workspace file  (heavy; SKIP_DOWNLOAD=1 to skip)
  5 dedup-guard      backend /capture on an already-live URL → 409
  6 synthesis        full pipeline → _inbox note — ONLY if a text provider has quota,
                     else SKIP with the cooldown table (the pipeline's one LLM stage)
  7 journey          create → file → grade → sync, no LLM involved so it never SKIPs:
                     journey-create            POST /notes lands a note in _inbox
                     journey-inbox-visible      it actually shows up on GET /inbox
                     journey-file               POST /inbox/file moves it into a real folder
                     journey-grade              POST /reviews/grade advances note_reviews.due
                     journey-frontmatter-mirror the fsrs-* fields land in the local .md file
                     journey-sync-queued        sync_queue picks up the edit (local-first)

Environmental honesty: exhausted free-tier LLM providers make phase 6 a SKIP, not a
FAIL — the deterministic pipeline is still proven. Self-cleaning: bundles, any
downloaded file, and any note/capture/journey artifact it creates are removed at the end.
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse

import requests

BACKEND  = os.environ.get("E2E_BACKEND",  "http://127.0.0.1:8084")
EMBEDDER = os.environ.get("E2E_EMBEDDER", "http://127.0.0.1:8000")
WRAPPER  = os.environ.get("E2E_WRAPPER",  "http://127.0.0.1:5500")
PAGE_URL  = "https://course.fast.ai/Lessons/lesson4.html"
VIDEO_URL = "https://www.youtube.com/watch?v=toUgBQv1BT8"
SKIP_DOWNLOAD = os.environ.get("SKIP_DOWNLOAD") == "1"

S = requests.Session()
RESULTS = []          # (name, verdict, seconds, detail)
CREATED_BUNDLES = []   # embedder job ids whose bundle json we should delete
CLEANUP_NOTES = []     # absolute inbox note paths to discard
CLEANUP_CAPTURES = []  # capture ids to mark discarded
CLEANUP_WORKSPACE = [] # downloaded _workspace filenames to remove

JOURNEY_FOLDER = "_e2e_journey"
CLEANUP_JOURNEY_NOTE = None    # final (filed) absolute path, to soft-delete
CLEANUP_JOURNEY_FOLDER = None  # absolute test folder, to soft-delete


def _psql(sql):
    return subprocess.run(
        ["docker", "exec", "obidianoptimizer-postgres-1", "psql", "-U", "obsidian",
         "-d", "obsidian", "-tA", "-c", sql],
        capture_output=True, text=True).stdout.strip()


def record(name, verdict, t0, detail=""):
    RESULTS.append((name, verdict, time.time() - t0, detail))
    mark = {"PASS": "✓", "FAIL": "✗", "SKIP": "–"}[verdict]
    print(f"  [{mark}] {name}: {verdict} ({time.time()-t0:.1f}s) {detail}", flush=True)


def poll_ingest(job_id, timeout, label):
    """Poll embedder /ingest/{id} until terminal; return the final job dict."""
    deadline = time.time() + timeout
    last_stage = None
    while time.time() < deadline:
        j = S.get(f"{EMBEDDER}/ingest/{job_id}", timeout=10).json()
        if j.get("stage") != last_stage:
            last_stage = j.get("stage")
            print(f"      {label}: stage={last_stage}", flush=True)
        if j.get("status") in ("DONE", "FAILED"):
            return j
        time.sleep(3)
    return {"status": "TIMEOUT", "stage": last_stage}


# ── phase 1: preflight ───────────────────────────────────────────────────────

def preflight():
    t0 = time.time()
    try:
        h = S.get(f"{EMBEDDER}/health", timeout=10).json()
        prov = S.get(f"{WRAPPER}/providers", timeout=10).json()
    except Exception as e:
        record("preflight", "FAIL", t0, f"stack unreachable: {e}")
        return False, {}
    pw = os.environ.get("APP_AUTH_PASSWORD") or _psql(
        "SELECT value FROM app_settings WHERE key='app.auth.password'") or None
    if not pw:
        pw = subprocess.run(
            ["docker", "exec", "obidianoptimizer-backend-1", "printenv", "APP_AUTH_PASSWORD"],
            capture_output=True, text=True).stdout.strip()
    user = subprocess.run(
        ["docker", "exec", "obidianoptimizer-backend-1", "printenv", "APP_AUTH_USERNAME"],
        capture_output=True, text=True).stdout.strip() or "admin"
    r = S.post(f"{BACKEND}/login", data={"username": user, "password": pw},
               allow_redirects=False, timeout=10)
    ok = r.status_code in (200, 302) and "JSESSIONID" in S.cookies.get_dict()
    detail = f"embedder={h.get('device')} model={h.get('model','?').split('/')[-1]}; login={'ok' if ok else r.status_code}"
    record("preflight", "PASS" if ok else "FAIL", t0, detail)
    return ok, prov


def text_provider_available(prov):
    """A text-chain provider with quota right now (cooldown 0, configured)."""
    text_chain = ["groq", "github", "mistral", "deepseek", "gemini", "claude-cli"]
    ready = [n for n in text_chain
             if prov.get(n, {}).get("configured") and prov.get(n, {}).get("cooldown_s", 1) == 0]
    return ready


# ── phase 2: video captions (yt-dlp fast-path, no LLM) ───────────────────────

def extract_video_captions():
    t0 = time.time()
    try:
        sub = S.post(f"{EMBEDDER}/ingest",
                     json={"ref": VIDEO_URL, "extract_only": True}, timeout=30).json()
        job_id = sub["id"]
        CREATED_BUNDLES.append(job_id)
    except Exception as e:
        record("video-captions", "FAIL", t0, f"submit failed: {e}")
        return
    j = poll_ingest(job_id, timeout=300, label="video")
    if j.get("status") != "DONE":
        record("video-captions", "FAIL", t0,
               f"status={j.get('status')} stage={j.get('stage')} err={str(j.get('error'))[:120]}")
        return
    segs, dur = j.get("segments", 0), j.get("duration_s", 0)
    ok = segs > 0 and dur > 0
    record("video-captions", "PASS" if ok else "FAIL", t0,
           f"{segs} transcript segments, duration {dur}s (yt-dlp captions)")


# ── phase 3: page web extraction (trafilatura, no LLM) ───────────────────────

def extract_page_web():
    t0 = time.time()
    try:
        sub = S.post(f"{EMBEDDER}/ingest",
                     json={"ref": PAGE_URL, "extract_only": True}, timeout=30).json()
        job_id = sub["id"]
        CREATED_BUNDLES.append(job_id)
    except Exception as e:
        record("page-web", "FAIL", t0, f"submit failed: {e}")
        return
    j = poll_ingest(job_id, timeout=120, label="page")
    if j.get("status") != "DONE":
        record("page-web", "FAIL", t0,
               f"status={j.get('status')} err={str(j.get('error'))[:120]}")
        return
    # peek the bundle text for topical sanity
    body = _read_bundle_text(j.get("bundle_path"))
    keywords = [k for k in ("NLP", "language", "transformer", "Hugging", "fine-tun", "model")
                if k.lower() in body.lower()]
    ok = j.get("segments", 0) > 0 and len(keywords) >= 2
    record("page-web", "PASS" if ok else "FAIL", t0,
           f"{j.get('segments')} segments, matched {keywords}")


def _read_bundle_text(bundle_path):
    if not bundle_path:
        return ""
    out = subprocess.run(
        ["docker", "exec", "obidianoptimizer-embedder-1", "cat", bundle_path],
        capture_output=True, text=True).stdout
    try:
        b = json.loads(out)
        return " ".join(s.get("text", "") for s in b.get("segments", []))
    except Exception:
        return out


# ── phase 4: yt-dlp full download (no LLM; heavy) ────────────────────────────

def ytdlp_download():
    t0 = time.time()
    if SKIP_DOWNLOAD:
        record("ytdlp-download", "SKIP", t0, "SKIP_DOWNLOAD=1")
        return
    try:
        r = S.post(f"{BACKEND}/download", json={"url": VIDEO_URL}, timeout=30)
        job = r.json()
        job_id = job.get("id")
    except Exception as e:
        record("ytdlp-download", "FAIL", t0, f"submit failed: {e}")
        return
    deadline = time.time() + 1800   # up to 30 min for a full lecture download
    last = None
    while time.time() < deadline:
        st = S.get(f"{BACKEND}/download/{job_id}", timeout=10).json()
        if st.get("status") != last:
            last = st.get("status")
            print(f"      download: status={last} {st.get('progress','')}% {st.get('speed','')}", flush=True)
        if st.get("status") in ("done", "error"):
            break
        time.sleep(5)
    else:
        record("ytdlp-download", "FAIL", t0, "timed out after 30m")
        return
    if st.get("status") != "done":
        record("ytdlp-download", "FAIL", t0, f"error: {str(st.get('error'))[:120]}")
        return
    fname = st.get("filename", "")
    exists = subprocess.run(
        ["docker", "exec", "obidianoptimizer-embedder-1", "sh", "-c",
         f'ls -la /workspace/ | grep -F "{os.path.basename(fname)}" | head -1'],
        capture_output=True, text=True).stdout.strip()
    ok = bool(fname) and bool(exists)
    if ok:
        CLEANUP_WORKSPACE.append(os.path.basename(fname))
    record("ytdlp-download", "PASS" if ok else "FAIL", t0,
           f"file={os.path.basename(fname)} ({exists.split()[4] if exists else '?'} bytes)")


# ── phase 5: dedup guard ─────────────────────────────────────────────────────

def dedup_guard():
    t0 = time.time()
    # only meaningful if a live capture already exists for the video
    live = _psql(f"SELECT count(*) FROM capture WHERE source_ref='{VIDEO_URL}' "
                 f"AND status IN ('queued','processing','ready')")
    r = S.post(f"{BACKEND}/capture", json={"url": VIDEO_URL}, timeout=15)
    if live and int(live) > 0:
        ok = r.status_code == 409 and r.json().get("duplicate") is True
        record("dedup-guard", "PASS" if ok else "FAIL", t0,
               f"live capture exists → expected 409, got {r.status_code}")
    else:
        # no live dup — a fresh capture should be accepted (200); track it for cleanup
        if r.status_code == 200:
            cid = r.json().get("captureId")
            if cid:
                CLEANUP_CAPTURES.append(cid)
            record("dedup-guard", "SKIP", t0, "no live dup present; fresh capture accepted (200)")
        else:
            record("dedup-guard", "FAIL", t0, f"unexpected {r.status_code}")


# ── phase 6: synthesis → inbox (needs an LLM provider) ───────────────────────

def synthesis(prov):
    t0 = time.time()
    ready = text_provider_available(prov)
    if not ready:
        cooldowns = ", ".join(
            f"{n}={prov[n]['cooldown_s']}s" for n in prov
            if prov[n].get("configured") and prov[n].get("cooldown_s"))
        record("synthesis", "SKIP", t0,
               f"all text providers cooling ({cooldowns}) — deterministic pipeline proven, "
               f"synthesis needs a provider window")
        return
    # a fresh standalone: use the page URL only if it isn't already a live capture
    live = _psql(f"SELECT count(*) FROM capture WHERE source_ref='{PAGE_URL}' "
                 f"AND status IN ('queued','processing','ready')")
    if live and int(live) > 0:
        record("synthesis", "SKIP", t0, "page URL already a live capture; skipping to avoid dup")
        return
    r = S.post(f"{BACKEND}/capture", json={"url": PAGE_URL}, timeout=15)
    if r.status_code != 200:
        record("synthesis", "FAIL", t0, f"capture {r.status_code}: {r.text[:120]}")
        return
    cid = r.json().get("captureId")
    CLEANUP_CAPTURES.append(cid)
    # poll the inbox for a standalone note sourced from the page
    deadline = time.time() + 600
    found = None
    while time.time() < deadline:
        items = S.get(f"{BACKEND}/inbox", timeout=15).json()
        for it in items:
            if it.get("source") == PAGE_URL and not it.get("inPlace"):
                found = it
                break
        if found:
            break
        time.sleep(10)
    if not found:
        record("synthesis", "FAIL", t0, f"no inbox note appeared for {PAGE_URL} in 10m (provider={ready[0]})")
        return
    CLEANUP_NOTES.append(found["path"])
    ok = bool(found.get("suggestedFolder")) and bool(found.get("content"))
    record("synthesis", "PASS" if ok else "FAIL", t0,
           f"note '{found.get('title')}' via {ready[0]}, suggested={found.get('suggestedFolder','')[:40]}")


# ── phase 7: journey — create → file → grade → sync (no LLM, never SKIPs) ───

def journey():
    """The chain the user actually doubts, end to end: a note created the way the
    Learn page creates one -> does it really land where the Inbox tab looks ->
    filing it out of _inbox -> grading it -> does FSRS really push the due date
    forward -> does the local edit actually get queued for Drive. Fully
    self-contained (creates its own note+folder) so it runs regardless of LLM
    provider quota — unlike phase 6, this should never SKIP."""
    global CLEANUP_JOURNEY_NOTE, CLEANUP_JOURNEY_FOLDER
    name = f"e2e-journey-{int(time.time())}"

    # folder/targetFolder must be absolute vault paths (validated server-side against
    # the real vault root) — discover it the same way the frontend does, via /children
    vault_root = S.get(f"{BACKEND}/children", timeout=15).json().get("parentPath")
    inbox_folder = f"{vault_root}/_inbox"

    # 7a. create — the same POST /notes the Learn "new note" action calls
    t0 = time.time()
    try:
        r = S.post(f"{BACKEND}/notes", json={"folder": inbox_folder, "name": name}, timeout=15)
        path = r.json().get("path") if r.status_code == 200 else None
    except Exception as e:
        record("journey-create", "FAIL", t0, f"request failed: {e}")
        return
    if not path:
        record("journey-create", "FAIL", t0, f"POST /notes {r.status_code}: {r.text[:150]}")
        return
    CLEANUP_JOURNEY_NOTE = path
    record("journey-create", "PASS", t0, f"created {path}")

    # 7b. it must actually surface on the Learn Inbox tab, same as a real capture would
    t0 = time.time()
    items = S.get(f"{BACKEND}/inbox", timeout=15).json()
    seen = any(it.get("path") == path for it in items)
    record("journey-inbox-visible", "PASS" if seen else "FAIL", t0,
           f"{'found' if seen else 'MISSING'} in GET /inbox ({len(items)} items)")
    if not seen:
        return

    # 7c. file it — the same POST /inbox/file the "File" button calls
    t0 = time.time()
    target_folder = f"{vault_root}/{JOURNEY_FOLDER}"
    content = S.get(f"{BACKEND}/text", params={"noteName": path}, timeout=15).text
    r = S.post(f"{BACKEND}/inbox/file",
               json={"path": path, "targetFolder": target_folder, "content": content}, timeout=15)
    if r.status_code != 200:
        record("journey-file", "FAIL", t0, f"POST /inbox/file {r.status_code}: {r.text[:150]}")
        return
    new_path = r.json().get("path")
    CLEANUP_JOURNEY_NOTE, CLEANUP_JOURNEY_FOLDER = new_path, target_folder
    still_inbox = "_inbox" in new_path
    record("journey-file", "FAIL" if still_inbox else "PASS", t0, f"moved to {new_path}")
    if still_inbox:
        return

    # 7d. grade it — the same POST /reviews/grade the review UI calls — and check
    # note_reviews.due (the actual /due index) moved forward, not just "changed"
    t0 = time.time()
    before = _psql(f"SELECT due FROM note_reviews WHERE note_path='{new_path}'")
    r = S.post(f"{BACKEND}/reviews/grade", json={"notePath": new_path, "band": "GOOD"}, timeout=15)
    if r.status_code != 200:
        record("journey-grade", "FAIL", t0, f"POST /reviews/grade {r.status_code}: {r.text[:150]}")
        return
    after = _psql(f"SELECT due FROM note_reviews WHERE note_path='{new_path}'")
    advanced_future = _psql(f"SELECT due > now() FROM note_reviews WHERE note_path='{new_path}'")
    ok = bool(after) and after != before and advanced_future == "t"
    record("journey-grade", "PASS" if ok else "FAIL", t0,
           f"note_reviews.due {before or '(none)'} -> {after or '(none)'}")
    if not ok:
        return

    # 7e. did the frontmatter mirror actually reach the local file (offline/volume-reset path)?
    t0 = time.time()
    fm = S.get(f"{BACKEND}/text", params={"noteName": new_path}, timeout=15).text
    mirrored = "fsrs-s:" in fm and "fsrs-last:" in fm
    record("journey-frontmatter-mirror", "PASS" if mirrored else "FAIL", t0,
           "fsrs-* fields present in local file" if mirrored else "fsrs-* fields MISSING from local file")

    # 7f. local-first: did the edit get queued for Drive upload?
    t0 = time.time()
    rel = new_path[new_path.index(JOURNEY_FOLDER):]
    status = _psql(f"SELECT status FROM sync_queue WHERE path='{rel}'")
    queued = status in ("PENDING", "DONE")  # DONE only if a sync cycle already drained it
    record("journey-sync-queued", "PASS" if queued else "FAIL", t0,
           f"sync_queue status={status or '(no row)'}")


# ── cleanup ──────────────────────────────────────────────────────────────────

def cleanup():
    print("\n── cleanup ──", flush=True)
    for note in CLEANUP_NOTES:
        try:
            S.delete(f"{BACKEND}/inbox", json={"path": note}, timeout=15)
            print(f"  discarded inbox note {os.path.basename(note)}")
        except Exception as e:
            print(f"  ! could not discard {note}: {e}")
    for cid in CLEANUP_CAPTURES:
        _psql(f"UPDATE capture SET status='discarded' WHERE id='{cid}'")
        print(f"  marked capture {cid} discarded")
    for jid in CREATED_BUNDLES:
        subprocess.run(["docker", "exec", "obidianoptimizer-embedder-1", "rm", "-f",
                        f"/models/ingest_bundles/{jid}.json"], capture_output=True)
    if CREATED_BUNDLES:
        print(f"  removed {len(CREATED_BUNDLES)} extraction bundle(s)")
    for fname in CLEANUP_WORKSPACE:
        subprocess.run(["docker", "exec", "obidianoptimizer-embedder-1", "sh", "-c",
                        f'rm -f "/workspace/{fname}"'], capture_output=True)
        print(f"  removed downloaded {fname}")
    if CLEANUP_JOURNEY_NOTE:
        try:
            S.delete(f"{BACKEND}/notes", json={"path": CLEANUP_JOURNEY_NOTE}, timeout=15)
            print(f"  soft-deleted journey note {os.path.basename(CLEANUP_JOURNEY_NOTE)}")
        except Exception as e:
            print(f"  ! could not delete journey note: {e}")
        rel = CLEANUP_JOURNEY_NOTE[CLEANUP_JOURNEY_NOTE.index(JOURNEY_FOLDER):] \
            if JOURNEY_FOLDER in CLEANUP_JOURNEY_NOTE else None
        if rel:
            _psql(f"DELETE FROM note_reviews WHERE note_path='{CLEANUP_JOURNEY_NOTE}'")
            _psql(f"DELETE FROM sync_queue WHERE path='{rel}'")
            print("  purged journey note_reviews/sync_queue rows")
    if CLEANUP_JOURNEY_FOLDER:
        try:
            S.delete(f"{BACKEND}/folders", json={"path": CLEANUP_JOURNEY_FOLDER}, timeout=15)
            print(f"  soft-deleted journey folder {CLEANUP_JOURNEY_FOLDER}")
        except Exception as e:
            print(f"  ! could not delete journey folder: {e}")


def main():
    print("══ LIVE INGESTION E2E ═══════════════════════════════════════", flush=True)
    print(f"  page:  {PAGE_URL}\n  video: {VIDEO_URL}\n", flush=True)
    ok, prov = preflight()
    if not ok:
        print("\npreflight failed — is the stack up? (docker compose ps)")
        return 2
    try:
        extract_video_captions()
        extract_page_web()
        ytdlp_download()
        dedup_guard()
        synthesis(prov)
        journey()
    finally:
        cleanup()

    print("\n══ SUMMARY ══════════════════════════════════════════════════", flush=True)
    fails = 0
    for name, verdict, secs, detail in RESULTS:
        print(f"  {name:16s} {verdict:5s} {secs:6.1f}s  {detail}")
        if verdict == "FAIL":
            fails += 1
    print("─────────────────────────────────────────────────────────────")
    print("  FAIL = real pipeline defect · SKIP = environmental (provider/dup)")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
