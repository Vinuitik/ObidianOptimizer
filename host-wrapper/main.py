import os
import base64
import json
import shutil
import subprocess
from pathlib import Path
from flask import Flask, request, jsonify
import anthropic
from dotenv import load_dotenv

load_dotenv()

app = Flask(__name__)
client = anthropic.Anthropic(api_key=os.environ["ANTHROPIC_API_KEY"])
VAULT_HOST_PATH = os.environ["VAULT_HOST_PATH"].replace("\\", "/")

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


@app.route("/process-image", methods=["POST"])
def process_image():
    data = request.json
    # container path: /vault/folder/image.png → host path
    host_path = Path(data["image_path"].replace("/vault", VAULT_HOST_PATH, 1))

    if not host_path.exists():
        return jsonify({"error": f"not found: {host_path}"}), 404

    media_type = MEDIA_TYPES.get(host_path.suffix.lower(), "image/png")
    image_data = base64.standard_b64encode(host_path.read_bytes()).decode()

    message = client.messages.create(
        model="claude-haiku-4-5-20251001",
        max_tokens=1024,
        messages=[{"role": "user", "content": [
            {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": image_data}},
            {"type": "text", "text": IMAGE_PROMPT}
        ]}]
    )
    return jsonify({"text": message.content[0].text})


CLI_TIMEOUT_S = int(os.environ.get("CLI_TIMEOUT_S", "180"))


@app.route("/complete", methods=["POST"])
def complete():
    """Text completion via the `claude` CLI (headless -p mode).

    Bills the Claude subscription's included credits, NOT API credits —
    that's the whole reason this endpoint exists. Used by the flashcard
    generation agent (embedder/flashcards/generate.py).

    Request:  {"prompt": str, "system"?: str, "model"?: str}
    Response: {"text": str} or {"error": str}
    """
    data = request.json or {}
    prompt = data.get("prompt", "")
    if not prompt:
        return jsonify({"error": "prompt required"}), 422

    # On Windows the npm-installed CLI is claude.cmd — which() resolves it,
    # avoiding shell=True and its quoting hazards.
    claude_bin = shutil.which("claude") or "claude"
    cmd = [claude_bin, "-p", "--output-format", "json",
           "--model", data.get("model", os.environ.get("SYNTH_MODEL", "haiku"))]
    if data.get("system"):
        cmd += ["--append-system-prompt", data["system"]]

    try:
        result = subprocess.run(
            cmd, input=prompt, capture_output=True, text=True,
            encoding="utf-8", timeout=CLI_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        return jsonify({"error": f"claude CLI timed out after {CLI_TIMEOUT_S}s"}), 504

    if result.returncode != 0:
        return jsonify({"error": f"claude CLI exit {result.returncode}: {result.stderr[:500]}"}), 502

    try:
        payload = json.loads(result.stdout)
        return jsonify({"text": payload.get("result", "")})
    except json.JSONDecodeError:
        # CLI printed plain text (older versions / unexpected format)
        return jsonify({"text": result.stdout.strip()})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5001)))
