import json

import pytest

import main


@pytest.fixture
def env(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(main, "_VAULTS_OVERRIDE", tmp_path / "docker-compose.vaults.yml")
    monkeypatch.setattr(main, "_VAULTS_REGISTRY", tmp_path / "vaults.registry.json")
    monkeypatch.setattr(main, "_run_compose", lambda: calls.append(1))
    monkeypatch.setattr(main, "VAULT_HOST_PATH", str(tmp_path / "primary"))
    monkeypatch.setattr(main, "_REPO_ROOT", tmp_path / "repo")
    for d in ("primary", "repo", "v2", "v3"):
        (tmp_path / d).mkdir()
    return tmp_path, calls, main.app.test_client()


def post(c, vid, path):
    return c.post("/vaults", json={"id": vid, "host_path": str(path)})


def test_two_vaults_render_all_binds(env):
    t, calls, c = env
    assert post(c, 2, t / "v2").status_code == 202
    assert post(c, 3, t / "v3").status_code == 202
    y = (t / "docker-compose.vaults.yml").read_text()
    for vid, d in ((2, "v2"), (3, "v3")):
        h = str(t / d)
        assert f'"{h}:/vaults/{vid}"' in y                      # backend rw
        assert f'"{h}:/vaults/{vid}:ro"' in y                   # embedder ro
        for sub in ("_reports", "_workspace", "resources"):
            assert f'"{h}/{sub}:/vaults/{vid}/{sub}"' in y      # embedder rw sub-binds
        assert f'"{h}/resources:/vaults-res/{vid}:ro"' in y     # nginx
        assert f'"{h}/_workspace:/vaults-ws/{vid}:ro"' in y
    assert (t / "v2" / "_reports").is_dir()
    assert len(calls) == 2


def test_idempotent(env):
    t, calls, c = env
    post(c, 2, t / "v2")
    before = (t / "docker-compose.vaults.yml").read_text()
    r = post(c, 2, t / "v2")
    assert r.status_code == 202 and r.get_json()["changed"] is False
    assert (t / "docker-compose.vaults.yml").read_text() == before
    assert len(calls) == 1
    assert json.loads((t / "vaults.registry.json").read_text()) == {"2": str(t / "v2")}


def test_rejects_missing_dir(env):
    t, calls, c = env
    assert post(c, 2, t / "nope").status_code == 400
    assert not calls


def test_rejects_overlap(env):
    t, calls, c = env
    post(c, 2, t / "v2")
    (t / "v2" / "sub").mkdir()
    assert post(c, 3, t / "v2" / "sub").status_code == 409     # inside another vault
    assert post(c, 3, t / "primary").status_code == 409        # primary
    assert post(c, 3, t / "repo").status_code == 409           # repo
    assert post(c, 3, t).status_code == 409                    # ancestor of the others
    assert post(c, 2, t / "v3").status_code == 409             # same id, different path
    assert post(c, 1, t / "v3").status_code == 400
    assert len(calls) == 1
