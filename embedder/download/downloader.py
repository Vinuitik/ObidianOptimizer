"""yt-dlp download core — salvaged from the former VideoManager sister app.

This is the whole reason VideoManager existed for us: pull captions (for the ingest
captions-fast-path) and download video/audio (for the offline-viewing feature) via
yt-dlp. It's small and dependency-light, so it now lives in-process here instead of
behind an HTTP hop to a separate service.

Two consumers:
  • ingest/extract_av.py  → fetch_subs()  (YouTube captions, no download)
  • main.py /download     → download_sync() via download/jobs.py (full download)

Output dir: DOWNLOAD_DIR env (default /workspace — bind-mounted to the vault's
_workspace/ in docker-compose so downloaded media shows up directly in the Learn
page, alongside URL-saved and uploaded files).
"""
import os
import tempfile
from pathlib import Path
from typing import Callable

import yt_dlp

DOWNLOAD_DIR = os.environ.get("DOWNLOAD_DIR", "/workspace")


def build_ydl_opts(progress_hook: Callable | None) -> dict:
    opts = {
        "outtmpl": f"{DOWNLOAD_DIR}/%(title)s.%(ext)s",
        "format": "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
        "merge_output_format": "mp4",
        "quiet": True,
        "no_warnings": True,
    }
    if progress_hook is not None:
        opts["progress_hooks"] = [progress_hook]
    return opts


def _parse_percent(raw: str) -> float:
    try:
        return float(raw.strip().replace("%", ""))
    except (ValueError, AttributeError):
        return 0.0


def download_sync(url: str, progress_hook: Callable | None = None) -> str:
    """Download a video/playlist URL to DOWNLOAD_DIR. Returns the output path of
    the (last) file. Blocking — callers run it on a worker thread (download/jobs)."""
    Path(DOWNLOAD_DIR).mkdir(parents=True, exist_ok=True)
    opts = build_ydl_opts(progress_hook)
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        # Playlists return an "entries" list; report the last entry's file.
        if isinstance(info, dict) and info.get("entries"):
            entries = [e for e in info["entries"] if e]
            info = entries[-1] if entries else info
        return ydl.prepare_filename(info)


def fetch_audio(url: str, dest_dir: str | None = None) -> Path:
    """Download bestaudio only, to a temp/given dir. Used by the YouTube
    force_whisper path (extract_av) — cheaper than pulling the full video.
    Returns the downloaded file path."""
    target = dest_dir or tempfile.mkdtemp(prefix="ytaudio_")
    opts = {
        "format": "bestaudio/best",
        "outtmpl": f"{target}/%(id)s.%(ext)s",
        "quiet": True,
        "no_warnings": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        return Path(ydl.prepare_filename(info))


def fetch_subs(url: str, lang: str = "en") -> dict:
    """Captions only, no video download (ingest captions-fast-path).

    Prefers manual subtitles over YouTube auto-subs. Returns
    {"vtt": str, "title": str, "duration_s": float} — raises if neither exists.
    """
    with tempfile.TemporaryDirectory() as tmp:
        opts = {
            "skip_download": True,
            "writesubtitles": True,
            "writeautomaticsub": True,
            "subtitleslangs": [lang],
            "subtitlesformat": "vtt",
            "outtmpl": f"{tmp}/subs.%(ext)s",
            "quiet": True,
            "no_warnings": True,
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
        vtts = sorted(Path(tmp).glob("*.vtt"))
        if not vtts:
            raise RuntimeError(f"no {lang} captions available for {url}")
        return {
            "vtt": vtts[0].read_text(encoding="utf-8"),
            "title": info.get("title", url),
            "duration_s": float(info.get("duration") or 0),
        }


def parse_progress(d: dict) -> dict:
    """Normalise a yt-dlp progress hook dict into a consistent shape."""
    return {
        "progress": _parse_percent(d.get("_percent_str", "0%")),
        "speed": d.get("_speed_str", "N/A").strip(),
        "eta": d.get("_eta_str", "N/A").strip(),
        "filename": d.get("filename", ""),
    }
