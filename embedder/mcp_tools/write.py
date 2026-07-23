"""MCP tools: turning resources/text into notes (the only write-capable tools).

Split out of mcp_server.py for readability — the FastMCP instance and the
vault-path helper these tools depend on still live there; this module just
registers @mcp.tool() functions onto it. Every write still goes through the
Java backend's internal API (ingest/publish.py), never a direct file write —
see publish.py's own docstring for why.
"""
import os

from mcp_server import mcp, _resolve_in_vault


@mcp.tool()
def ingest_resource(ref: str, force_whisper: bool = False) -> dict:
    """Turn a resource into Obsidian notes (async job). ref is a vault-relative
    path (resources/videos/x.mp4, folder/slides.pdf) or a URL (article,
    YouTube). Returns the job descriptor — poll status via job id at
    GET /ingest/{id}. Pipeline: deterministic extraction (whisper/PyMuPDF/
    trafilatura/keyframes) → constrained LLM synthesis → notes via backend."""
    from ingest import jobs as ingest_jobs
    from ingest import router as ingest_router

    ingest_router.route(ref)  # raises ValueError on unroutable input
    resolved = None
    if not ref.startswith(("http://", "https://")):
        resolved = _resolve_in_vault(ref)
        if not resolved.exists():
            raise ValueError(f"not in vault: {ref}")
    return ingest_jobs.submit(ref, resolved, force_whisper)


@mcp.tool()
def split_note(note_path: str) -> dict:
    """Split an oversized note (>6000 chars) into focused concept notes and
    rewrite the original as a hub of [[links]]. Synchronous — a few LLM calls."""
    from ingest import split_note as splitter

    content = _resolve_in_vault(note_path).read_text(encoding="utf-8")
    return splitter.split(note_path, content)


@mcp.tool()
def create_note(text: str, title: str = "", already_processed: bool = True, folder: str = "") -> dict:
    """Create a note from text YOU authored. Don't hand-write a note file — pass the
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
    GET /ingest/{id} (re-process). `title` is an optional display hint."""
    from ingest import jobs as ingest_jobs

    if not text or not text.strip():
        raise ValueError("text is empty")

    # Default: LLM-authored content is already good — stage it as-is (no tokens, no queue,
    # durable synchronous write). See [[mcp-ingest-durability-gap]] for the reasoning.
    if already_processed:
        return _stage_note_as_is(text.strip(), title, folder.strip())

    split_min = int(os.environ.get("INGEST_SPLIT_MIN_WORDS", "700"))
    if len(text.split()) < split_min:
        return _stage_note_as_is(text.strip(), title, folder.strip())

    return ingest_jobs.submit("", None, text=text, source_type="text",
                              title=(title or None))


def _stage_note_as_is(text: str, title: str = "", folder: str = "") -> dict:
    """Short-note path: stage the text with NO LLM (no split, no re-synthesis) — the note
    is its own source. Reuses the ingest publish helpers so it lands in the vault exactly
    like a synthesized note, minus the token spend. `folder` set → a NAMED SUBFOLDER OF
    THE INBOX (agent-chosen grouping, marked `ingest-auto-filed`), never outside it; empty
    → the inbox root (`ingest-inbox` + classifier-suggested folder, awaiting user review).
    Either way the note lands under `publish.INBOX_FOLDER` — this function has no path to
    write anywhere else in the vault."""
    from ingest import publish, synthesize
    t = (title or "").strip() or _title_from_text(text)
    bundle = {"source": {"ref": "", "title": t, "type": "text"}, "media": []}
    note_md = synthesize.assemble(bundle, {"title": t, "tags": []}, [], text)
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
        note_md = publish.stamp_auto_filed(note_md)
    else:
        target = publish.INBOX_FOLDER
        note_md = publish.stamp_inbox(note_md, "", publish.find_home(t))
    publish.ensure_folder(target)
    path = publish.create_note(target, synthesize.slugify(t), note_md)
    return {"status": "DONE", "ingested": False, "words": len(text.split()),
            "notes_created": [path]}


def _title_from_text(text: str) -> str:
    first = next((ln.strip().lstrip("# ").strip() for ln in text.splitlines() if ln.strip()), "")
    return (first[:80] or "Note")
