"""Flask endpoint tests — /health, /providers, /process-image, /complete.

Router calls are monkeypatched on the module-level `main.router` instance.
"""
import pytest

import llm_router
import main


@pytest.fixture
def client():
    main.app.config["TESTING"] = True
    return main.app.test_client()


@pytest.fixture
def vault_image(tmp_path, monkeypatch):
    """Point the /vault translation at a tmp dir containing one PNG."""
    img = tmp_path / "resources" / "images" / "shot.png"
    img.parent.mkdir(parents=True)
    img.write_bytes(b"\x89PNG fake")
    monkeypatch.setattr(main, "VAULT_HOST_PATH", str(tmp_path).replace("\\", "/"))
    return img


def test_health(client):
    res = client.get("/health")
    assert res.status_code == 200
    assert res.get_json() == {"status": "ok"}


def test_providers_returns_router_status(client):
    body = client.get("/providers").get_json()
    assert "gemini" in body
    assert {"configured", "in_flight", "cooldown_s", "ok", "failed"} <= set(body["gemini"])


# ── /process-image ───────────────────────────────────────────────────────

def test_process_image_translates_vault_path(client, vault_image, monkeypatch):
    captured = {}

    def fake_vision(prompt, image_bytes, media_type, priority=None):
        captured.update(prompt=prompt, bytes=image_bytes, media_type=media_type)
        return "extracted", "gemini"

    monkeypatch.setattr(main.router, "complete_vision", fake_vision)
    res = client.post("/process-image",
                      json={"image_path": "/vault/resources/images/shot.png"})
    assert res.status_code == 200
    assert res.get_json() == {"text": "extracted", "provider": "gemini"}
    assert captured["bytes"] == b"\x89PNG fake"
    assert captured["media_type"] == "image/png"
    assert captured["prompt"] == main.IMAGE_PROMPT


def test_process_image_404_when_missing(client, vault_image):
    res = client.post("/process-image", json={"image_path": "/vault/nope.png"})
    assert res.status_code == 404
    assert "not found" in res.get_json()["error"]


def test_process_image_503_when_router_exhausted(client, vault_image, monkeypatch):
    def exhausted(*a, **k):
        raise llm_router.RouterError("all providers down")
    monkeypatch.setattr(main.router, "complete_vision", exhausted)
    res = client.post("/process-image",
                      json={"image_path": "/vault/resources/images/shot.png"})
    assert res.status_code == 503


def test_process_image_unknown_extension_defaults_png(client, vault_image, monkeypatch):
    odd = vault_image.parent / "frame.bmp"
    odd.write_bytes(b"bmp")
    captured = {}

    def fake_vision(prompt, image_bytes, media_type, priority=None):
        captured["media_type"] = media_type
        return "t", "gemini"

    monkeypatch.setattr(main.router, "complete_vision", fake_vision)
    client.post("/process-image",
                json={"image_path": "/vault/resources/images/frame.bmp"})
    assert captured["media_type"] == "image/png"


# ── /process-images (batch) ──────────────────────────────────────────────

def test_process_images_aligns_results_with_missing_files(client, vault_image, monkeypatch):
    def fake_batch(single_prompt, batch_tmpl, images, priority=None):
        return [f"text-{i}" for i in range(len(images))], "gemini"

    monkeypatch.setattr(main.router, "complete_vision_batch", fake_batch)
    res = client.post("/process-images", json={"image_paths": [
        "/vault/resources/images/shot.png",
        "/vault/resources/images/missing.png",
        "/vault/resources/images/shot.png",
    ]})
    assert res.status_code == 200
    body = res.get_json()
    assert body["provider"] == "gemini"
    assert body["results"][0] == {"text": "text-0"}
    assert body["results"][1] == {"error": "not_found"}
    assert body["results"][2] == {"text": "text-1"}


def test_process_images_422_on_empty(client):
    assert client.post("/process-images", json={}).status_code == 422


def test_process_images_503_when_exhausted(client, vault_image, monkeypatch):
    def exhausted(*a, **k):
        raise llm_router.RouterError("exhausted")
    monkeypatch.setattr(main.router, "complete_vision_batch", exhausted)
    res = client.post("/process-images", json={
        "image_paths": ["/vault/resources/images/shot.png"]})
    assert res.status_code == 503


def test_process_images_all_missing_skips_router(client, vault_image, monkeypatch):
    def boom(*a, **k):
        raise AssertionError("router must not be called with zero images")
    monkeypatch.setattr(main.router, "complete_vision_batch", boom)
    res = client.post("/process-images",
                      json={"image_paths": ["/vault/nope1.png", "/vault/nope2.png"]})
    assert res.status_code == 200
    assert res.get_json()["results"] == [{"error": "not_found"}] * 2


# ── /complete ────────────────────────────────────────────────────────────

def test_complete_routes_through_text_chain(client, monkeypatch):
    captured = {}

    def fake_text(prompt, system=None, cli_model=None, priority=None):
        captured.update(prompt=prompt, system=system, cli_model=cli_model)
        return "answer", "groq"

    monkeypatch.setattr(main.router, "complete_text", fake_text)
    res = client.post("/complete", json={
        "prompt": "make cards", "system": "you are terse", "model": "sonnet"})
    assert res.status_code == 200
    assert res.get_json() == {"text": "answer", "provider": "groq"}
    assert captured == {"prompt": "make cards", "system": "you are terse",
                        "cli_model": "sonnet"}


def test_complete_422_on_missing_prompt(client):
    assert client.post("/complete", json={}).status_code == 422
    assert client.post("/complete", json={"prompt": ""}).status_code == 422


def test_complete_503_when_router_exhausted(client, monkeypatch):
    def exhausted(*a, **k):
        raise llm_router.RouterError("exhausted")
    monkeypatch.setattr(main.router, "complete_text", exhausted)
    assert client.post("/complete", json={"prompt": "x"}).status_code == 503
