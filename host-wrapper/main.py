import os
from pathlib import Path

from dotenv import load_dotenv

# SSOT: all credentials live in the repo-root .env. The local host-wrapper/.env
# is an optional override (e.g. a different PORT) and wins when both define a key.
_ROOT_ENV = Path(__file__).resolve().parent.parent / ".env"
load_dotenv(_ROOT_ENV)
load_dotenv(Path(__file__).resolve().parent / ".env", override=True)

from flask import Flask, request, jsonify  # noqa: E402

import llm_router  # noqa: E402  (reads env at import — keep after load_dotenv)

app = Flask(__name__)
router = llm_router.Router()

# host-wrapper historically used VAULT_HOST_PATH; the root .env calls it
# HOST_VAULT_PATH (same value, used by docker-compose). Accept either.
VAULT_HOST_PATH = (os.environ.get("VAULT_HOST_PATH")
                   or os.environ["HOST_VAULT_PATH"]).replace("\\", "/")

MEDIA_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
               ".gif": "image/gif", ".webp": "image/webp"}

IMAGE_PROMPT = (
    "Extract all visible text exactly as written. "
    "If this is a diagram, chart, or visual structure, describe its key elements "
    "and relationships concisely. Output only the extracted content, no preamble."
)


@app.route("/health")
def health():
    return {"status": "ok"}


@app.route("/providers")
def providers():
    """Router introspection: configured providers, cooldowns, ok/fail counts."""
    return jsonify(router.status())


@app.route("/process-image", methods=["POST"])
def process_image():
    data = request.json
    # container path: /vault/folder/image.png → host path
    host_path = Path(data["image_path"].replace("/vault", VAULT_HOST_PATH, 1))

    if not host_path.exists():
        return jsonify({"error": f"not found: {host_path}"}), 404

    media_type = MEDIA_TYPES.get(host_path.suffix.lower(), "image/png")

    try:
        text, provider = router.complete_vision(
            IMAGE_PROMPT, host_path.read_bytes(), media_type)
    except llm_router.RouterError as e:
        return jsonify({"error": str(e)}), 503

    return jsonify({"text": text, "provider": provider})


@app.route("/complete", methods=["POST"])
def complete():
    """Text completion through the router (free providers first, Claude CLI last).

    Request:  {"prompt": str, "system"?: str, "model"?: str}
    Response: {"text": str, "provider": str} or {"error": str}
    The "model" field only applies when the claude-cli provider is reached.
    """
    data = request.json or {}
    prompt = data.get("prompt", "")
    if not prompt:
        return jsonify({"error": "prompt required"}), 422

    try:
        text, provider = router.complete_text(
            prompt, system=data.get("system"), cli_model=data.get("model"))
    except llm_router.RouterError as e:
        return jsonify({"error": str(e)}), 503

    return jsonify({"text": text, "provider": provider})


if __name__ == "__main__":
    # threaded so concurrent image requests can shard across providers
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5001)), threaded=True)
