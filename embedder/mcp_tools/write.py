"""MCP tools: turning resources/text into notes (the only write-capable tools).

Split out of mcp_server.py for readability — the FastMCP instance and the
vault-path helper these tools depend on still live there; this module just
registers @mcp.tool() functions onto it. Every write still goes through the
Java backend's internal API (ingest/publish.py), never a direct file write —
see publish.py's own docstring for why.
"""
import logging
import os

import mcp_server
from mcp_server import mcp, _resolve_in_vault

log = logging.getLogger("embedder.mcp_tools.write")

_NON_CONTENT_TOP_LEVEL = {"_inbox", "_trash", "resources", "_workspace", "_reports"}


def _top_level_folders_hint() -> str:
    """Comma list of the vault's real top-level folders, for grounding create_note's
    `folder` docstring in actual taxonomy instead of the agent inventing names blind.
    Filesystem read (no DB/embedder dependency) so it's safe at import time. Computed
    ONCE, at process start — not live: a folder added after the embedder container is
    up won't show here until the next restart. Fine for top-level areas (rarely change);
    would need a real fix if this ever needs to reflect same-session vault edits."""
    try:
        names = sorted(
            p.name for p in mcp_server.VAULT_DIR.iterdir()
            if p.is_dir() and not p.name.startswith(".") and p.name not in _NON_CONTENT_TOP_LEVEL
        )
        return ", ".join(names) if names else "(vault has no top-level folders yet)"
    except OSError:
        return "(unavailable)"


@mcp.tool()
def ingest_resource(ref: str, force_whisper: bool = False, track_id: str = "") -> dict:
    """Turn a resource into Obsidian notes (async job). ref is a vault-relative
    path (resources/videos/x.mp4, folder/slides.pdf) or a URL (article,
    YouTube). Returns the job descriptor — poll status via job id at
    GET /ingest/{id}. Pipeline: deterministic extraction (whisper/PyMuPDF/
    trafilatura/keyframes) → constrained LLM synthesis → notes via backend.

    track_id (optional, default ""): tag the note(s) this produces onto an existing
    Learning Track (see the create_track tool) — each synthesized note is appended as
    a track item once synthesis actually finishes (this call returns long before that).
    Empty (default) preserves today's exact untagged behavior."""
    from ingest import jobs as ingest_jobs
    from ingest import router as ingest_router

    ingest_router.route(ref)  # raises ValueError on unroutable input
    resolved = None
    if not ref.startswith(("http://", "https://")):
        resolved = _resolve_in_vault(ref)
        if not resolved.exists():
            raise ValueError(f"not in vault: {ref}")
    return ingest_jobs.submit(ref, resolved, force_whisper, track_id=(track_id.strip() or None))


@mcp.tool()
def create_track(title: str, type: str = "") -> dict:
    """Create a new Learning Track (tracks/FLOWS.md) — a structured curriculum (a book,
    a course, an article series) the user can attach resources to over time via
    ingest_resource(track_id=...)/create_note(track_id=...), in this conversation or a
    later one. Use this when the user decides mid-conversation "let's make this a
    course" — it creates an empty track ready to receive material as you go; there is
    no separate step required before attaching the first resource. `type` is free text
    (book/course/article/custom) purely for display — default "custom" if omitted.
    Returns the created track, including its id for track_id params elsewhere."""
    from ingest import publish

    if not title or not title.strip():
        raise ValueError("title is empty")
    return publish.create_track(title.strip(), type.strip() or "custom")


@mcp.tool()
def split_note(note_path: str) -> dict:
    """Split an oversized note (>6000 chars) into focused concept notes and
    rewrite the original as a hub of [[links]]. Synchronous — a few LLM calls."""
    from ingest import split_note as splitter

    content = _resolve_in_vault(note_path).read_text(encoding="utf-8")
    return splitter.split(note_path, content)


def create_note(text: str, title: str = "", already_processed: bool = True, folder: str = "",
                track_id: str = "") -> dict:
    from ingest import jobs as ingest_jobs

    if not text or not text.strip():
        raise ValueError("text is empty")

    # Default: LLM-authored content is already good — stage it as-is (no tokens, no queue,
    # durable synchronous write). See [[mcp-ingest-durability-gap]] for the reasoning.
    if already_processed:
        return _stage_note_as_is(text.strip(), title, folder.strip(), track_id.strip())

    split_min = int(os.environ.get("INGEST_SPLIT_MIN_WORDS", "700"))
    if len(text.split()) < split_min:
        return _stage_note_as_is(text.strip(), title, folder.strip(), track_id.strip())

    return ingest_jobs.submit("", None, text=text, source_type="text",
                              title=(title or None), track_id=(track_id.strip() or None))


# Built once at import time — see _top_level_folders_hint's docstring for why this can't
# just be an f-string docstring literal (FastMCP reads __doc__ at decoration time below).
create_note.__doc__ = f"""Create a note from text YOU authored. Don't hand-write a note file — pass the
    whole body here.

    `folder` (optional, default ""): leave empty to stage the note directly in the Learn
    Inbox root. Pass a name (e.g. "Mobile PWA Notes") to group several notes from one
    conversation into their OWN named subfolder — but this is ALWAYS created under the
    Inbox (`_inbox/<folder>/`), NEVER elsewhere in the vault. There is no way to write
    outside the Inbox from this tool — every note still awaits the user's review-and-file
    step in the Learn page, grouped notes just get their own folder in that queue instead
    of landing flat. The note is stamped `ingest-auto-filed: true` so the user can see at a
    glance it didn't go through LLM re-synthesis. `folder` only applies to the synchronous
    as-is path below — the async re-process path always still goes to the inbox root.
    Existing top-level vault areas, for context/judgment when naming `folder` (NOT a menu
    it must match — this only ever creates a subfolder under `_inbox`): {_top_level_folders_hint()}.
    You do NOT need to guess a real destination here — every note (grouped or not) already
    gets a `suggested_folder` computed automatically from its own content and shown to the
    user at review time; `find_home_for_note` is there if you want to preview that pick.

    `already_processed` (default True): the text was authored by an LLM (you), so it is
    already good — stage it AS-IS with NO further LLM synthesis and NO async job. The
    write is SYNCHRONOUS: the note exists on disk when this returns, so it is durable
    without needing the async ingest queue, and it never re-spends tokens re-processing
    content tokens already produced. This is the normal path for agent-authored notes.

    Pass `already_processed=False` ONLY to deliberately treat the text as a RAW source to
    be re-processed by the ingest pipeline (deterministically segmented into linked concept
    notes when long). That spends tokens and runs async — use it for pasted third-party
    material, not for your own output. Short inputs stage as-is either way (splitting a
    short note is meaningless). Returns notes_created (as-is) or a job descriptor to poll
    GET /ingest/{{id}} (re-process). `title` is an optional display hint.

    `track_id` (optional, default ""): tag this note onto an existing Learning Track (see
    the create_track tool) as one of its items. Empty (default) preserves today's exact
    untagged behavior."""
create_note = mcp.tool()(create_note)


def _stage_note_as_is(text: str, title: str = "", folder: str = "", track_id: str = "") -> dict:
    """Short-note path: stage the text with NO LLM (no split, no re-synthesis) — the note
    is its own source. Reuses the ingest publish helpers so it lands in the vault exactly
    like a synthesized note, minus the token spend. `folder` set → a NAMED SUBFOLDER OF
    THE INBOX (agent-chosen grouping, marked `ingest-auto-filed`), never outside it; empty
    → the inbox root (`ingest-inbox` + classifier-suggested folder, awaiting user review).
    Either way the note lands under `publish.INBOX_FOLDER` — this function has no path to
    write anywhere else in the vault. `track_id` set → the note is also appended as a
    track item (synchronous, since this whole path is synchronous) — best-effort, a
    track-tagging hiccup must never fail the note write itself."""
    from ingest import publish, synthesize
    t = (title or "").strip() or _title_from_text(text)
    bundle = {"source": {"ref": "", "title": t, "type": "text"}, "media": []}
    note_md = synthesize.assemble(bundle, {"title": t, "tags": []}, [], text)
    # Computed regardless of grouping — batching notes into a named review-queue subfolder
    # (below) shouldn't cost a note its individual placement suggestion.
    suggested = publish.find_home(t)
    sub = folder.strip().strip("/")
    if sub:
        # Always nested under the inbox — strip an accidental leading "_inbox/" from the
        # caller so it can't double up, but there is no way to escape the inbox from here.
        prefix = publish.INBOX_FOLDER + "/"
        if sub == publish.INBOX_FOLDER:
            sub = ""
        elif sub.startswith(prefix):
            sub = sub[len(prefix):]
        target = f"{publish.INBOX_FOLDER}/{sub}" if sub else publish.INBOX_FOLDER
        note_md = publish.stamp_auto_filed(note_md, suggested)
    else:
        target = publish.INBOX_FOLDER
        note_md = publish.stamp_inbox(note_md, "", suggested)
    publish.ensure_folder(target)
    path = publish.create_note(target, synthesize.slugify(t), note_md)
    if track_id:
        try:
            publish.add_track_item(track_id, t, path)
        except Exception as e:
            log.warning("track item link failed (track_id=%s, note=%r): %s", track_id, t, e)
    return {"status": "DONE", "ingested": False, "words": len(text.split()),
            "notes_created": [path]}


def _title_from_text(text: str) -> str:
    first = next((ln.strip().lstrip("# ").strip() for ln in text.splitlines() if ln.strip()), "")
    return (first[:80] or "Note")
