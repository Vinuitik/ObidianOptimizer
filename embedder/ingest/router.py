"""Stage 1 router — deterministic: extension / URL pattern → extractor route.

No LLM, no content sniffing. If it's not in the table, the job fails loudly
instead of guessing (INGEST_AGENT_ARCH stage 1).
"""
import re
from urllib.parse import urlparse

YOUTUBE_RE = re.compile(
    r"(?:youtube\.com/(?:watch|shorts|live)|youtu\.be/)", re.IGNORECASE)

# extension → route
ROUTE_TABLE = {
    ".pdf": "pdf",
    ".mp4": "av", ".mkv": "av", ".webm": "av", ".mov": "av",
    ".mp3": "av", ".m4a": "av", ".wav": "av", ".ogg": "av", ".flac": "av",
    ".jpg": "image", ".jpeg": "image", ".png": "image", ".webp": "image",
}

AUDIO_EXTS = {".mp3", ".m4a", ".wav", ".ogg", ".flac"}


def route(ref: str) -> str:
    """Returns one of: av, youtube, web, pdf, image. Raises ValueError otherwise."""
    if ref.startswith(("http://", "https://")):
        if YOUTUBE_RE.search(ref):
            return "youtube"
        # a direct file URL still routes by its path extension
        path = urlparse(ref).path.lower()
        for ext, r in ROUTE_TABLE.items():
            if path.endswith(ext):
                return r
        return "web"
    dot = ref.lower().rfind(".")
    ext = ref.lower()[dot:] if dot != -1 else ""
    if ext in ROUTE_TABLE:
        return ROUTE_TABLE[ext]
    raise ValueError(f"no route for input: {ref!r} (ext {ext!r})")


def is_audio(ref: str) -> bool:
    dot = ref.lower().rfind(".")
    return dot != -1 and ref.lower()[dot:] in AUDIO_EXTS
