"""Agent report writer — writes when enabled, no-ops when off, never raises."""
import agent_reports
from agent_reports import AgentReport


def test_writes_a_report_with_all_sections(tmp_path, monkeypatch):
    monkeypatch.setattr(agent_reports, "ENABLED", True)
    monkeypatch.setattr(agent_reports, "REPORTS_DIR", str(tmp_path))

    rep = AgentReport("flashcards", "/vault/ml/FSRS.md")
    rep.input("prompt", "generate cards")
    rep.said("raw", '[{"type":"mcq"}]')
    rep.output("result", {"stored": 1})
    rep.save(status="ok")

    files = list((tmp_path / "flashcards").glob("*.md"))
    assert len(files) == 1
    body = files[0].read_text()
    assert "FSRS.md" in files[0].name and "ok" in files[0].name
    assert "INPUT · prompt" in body
    assert "MODEL · raw" in body
    assert "OUTPUT · result" in body
    assert "stored" in body


def test_disabled_is_a_noop(tmp_path, monkeypatch):
    monkeypatch.setattr(agent_reports, "ENABLED", False)
    monkeypatch.setattr(agent_reports, "REPORTS_DIR", str(tmp_path))
    AgentReport("flashcards", "x").input("a", "b").save()
    assert not list(tmp_path.glob("**/*.md"))


def test_save_never_raises_on_unwritable_dir(monkeypatch):
    monkeypatch.setattr(agent_reports, "ENABLED", True)
    monkeypatch.setattr(agent_reports, "REPORTS_DIR", "/proc/cannot/write/here")
    # Must swallow the filesystem error — reporting can't break the agent.
    AgentReport("flashcards", "x").output("r", {"k": 1}).save()


def test_prunes_to_keep_newest(tmp_path, monkeypatch):
    monkeypatch.setattr(agent_reports, "ENABLED", True)
    monkeypatch.setattr(agent_reports, "REPORTS_DIR", str(tmp_path))
    monkeypatch.setattr(agent_reports, "KEEP", 3)
    for i in range(6):
        AgentReport("ingest-outline", f"src{i}").output("r", i).save()
    assert len(list((tmp_path / "ingest-outline").glob("*.md"))) == 3
