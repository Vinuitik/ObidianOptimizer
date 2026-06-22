"""
Agent run reports — one human-readable file per agent invocation so you can see
EXACTLY where a run went wrong, without `docker exec` spelunking through logs.

Each report captures the three things that matter when an agent misbehaves:
  • INPUT  — what the harness/tools/preprocessing actually handed the model
             (post-enrichment note text, the rendered prompt, the segments…)
  • MODEL  — what the model SAID, raw and pre-parse (the JSON, the prose, retries)
  • OUTPUT — the final result that left the agent (stored cards, planned notes…)

Files land under AGENT_REPORTS_DIR/<agent>/<timestamp>_<status>_<subject>.md.
AGENT_REPORTS_DIR defaults to /reports, a host-mounted vault subdir (`_reports/`)
so the files are readable straight from the host. `_reports` is in the vault
RETRIEVAL IGNORE LISTS (FileRepository.EXCLUDED_DIRS on the Java side, and the
embedder's os.walk prune in main.py) so reports are never scanned, embedded, or
turned into cards.

Best-effort by contract: a reporting failure (read-only mount, disk full, bad
perms) must NEVER break the agent it observes. Every filesystem touch is guarded;
on failure we log a warning and move on. Disable entirely with AGENT_REPORTS=off.
"""
import json
import logging
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path

log = logging.getLogger("embedder.agent_reports")

REPORTS_DIR = os.environ.get("AGENT_REPORTS_DIR", "/reports")
ENABLED = os.environ.get("AGENT_REPORTS", "on").lower() != "off"
KEEP = int(os.environ.get("AGENT_REPORTS_KEEP", "300"))  # newest-N kept per agent


def _stringify(content) -> str:
    if isinstance(content, str):
        return content
    try:
        return json.dumps(content, ensure_ascii=False, indent=2, default=str)
    except Exception as e:  # noqa: BLE001 — never raise out of a debug helper
        return f"<unserializable: {e!r}>"


def _slug(subject: str) -> str:
    base = re.split(r"[\\/]", subject)[-1]
    base = re.sub(r"\.md$", "", base)  # avoid FSRS.md.md double extension
    return re.sub(r"[^A-Za-z0-9._-]+", "-", base).strip("-")[:80] or "run"


class AgentReport:
    """Collects labelled sections, then writes one markdown file on save().

    Usage:
        rep = AgentReport("flashcards", note_path)
        rep.input("note_content", content).input("prompt", prompt)
        rep.said("llm_raw", raw)
        rep.output("stored", result)
        rep.save(status="ok")          # or status="empty" / "error"

    Reuse the same instance across all stages of one run so the file shows the
    full story in order. All methods are no-ops when AGENT_REPORTS=off.
    """

    def __init__(self, agent: str, subject: str):
        self.agent = agent
        self.subject = subject
        self.started = time.time()
        self.sections: list[tuple[str, str]] = []

    def add(self, label: str, content) -> "AgentReport":
        if ENABLED:
            self.sections.append((label, _stringify(content)))
        return self

    # Semantic aliases for the three buckets we always care about.
    def input(self, label: str, content) -> "AgentReport":
        return self.add(f"INPUT · {label}", content)

    def said(self, label: str, content) -> "AgentReport":
        return self.add(f"MODEL · {label}", content)

    def output(self, label: str, content) -> "AgentReport":
        return self.add(f"OUTPUT · {label}", content)

    def save(self, status: str = "ok") -> None:
        if not ENABLED:
            return
        try:
            out_dir = Path(REPORTS_DIR) / self.agent
            out_dir.mkdir(parents=True, exist_ok=True)
            ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S_%f")
            path = out_dir / f"{ts}_{status}_{_slug(self.subject)}.md"
            elapsed = time.time() - self.started
            lines = [
                f"# {self.agent} · {self.subject}",
                f"_{datetime.now(timezone.utc).isoformat()} · "
                f"status={status} · {elapsed:.1f}s_",
                "",
            ]
            for label, content in self.sections:
                lines += [f"## {label}", "", "```", content, "```", ""]
            path.write_text("\n".join(lines), encoding="utf-8")
            _prune(out_dir)
        except Exception as e:  # noqa: BLE001 — reporting must never break the agent
            log.warning("[agent_reports] could not write %s/%s report: %s",
                        self.agent, self.subject, e)


def _prune(out_dir: Path) -> None:
    """Keep only the newest KEEP files per agent so a busy vault doesn't grow
    unbounded. Best-effort — pruning failures are swallowed."""
    try:
        files = sorted(out_dir.glob("*.md"), key=lambda p: p.stat().st_mtime, reverse=True)
        for stale in files[KEEP:]:
            stale.unlink(missing_ok=True)
    except Exception:  # noqa: BLE001
        pass
