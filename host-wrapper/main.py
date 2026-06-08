import os
import base64
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


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5001)))
