"""Stage 1 router — deterministic: extension / URL pattern → extractor route.

No LLM, no content sniffing. If it's not in the table, the job fails loudly
instead of guessing (INGEST_AGENT_ARCH stage 1).
"""
import re
from urllib.parse import urlparse

# Remote video / short-form platforms yt-dlp can resolve. A URL matching here routes to the
# A/V branch (`extract_av`'s remote path: captions → whisper fallback, all yt-dlp, so it's
# site-agnostic — reels have no captions and fall straight to whisper). The route VALUE stays
# "youtube" because that branch IS the yt-dlp remote path, not literally YouTube.
#
# Patterns are deliberately NARROW: only a specific video/reel/status matches, never a bare
# profile/home (instagram.com/<user> → web, but instagram.com/reel/<id> → video). Add hosts
# here as they come up. Instagram/TikTok are best-effort (login walls / rate limits / cookies).
VIDEO_HOST_RE = re.compile(
    r"""(?:
        youtube\.com/(?:watch|shorts|live)            |
        youtu\.be/                                    |
        instagram\.com/(?:reel|reels|p|tv)/           |
        tiktok\.com/@[\w.-]+/video/                   |
        tiktok\.com/(?:v|embed|t)/                    |
        vimeo\.com/\d+                                |
        player\.vimeo\.com/video/                     |
        (?:twitter|x)\.com/[^/]+/status/              |
        facebook\.com/(?:watch|reel|[\w.-]+/videos/)  |
        dailymotion\.com/video/
    )""",
    re.IGNORECASE | re.VERBOSE,
)

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
        if VIDEO_HOST_RE.search(ref):
            return "youtube"   # remote video platform → yt-dlp captions→whisper branch
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
