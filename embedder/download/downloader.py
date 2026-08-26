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
# Permanent media store the ingest downloads into so notes can play the LOCAL file
# (LOCAL_MEDIA_RETENTION stage 2). Writable sub-bind of the vault's resources/ — the
# embedder's /vault is :ro, so this dedicated rw mount is how it persists there.
RESOURCE_DIR = os.environ.get("RESOURCE_DIR", "/resources")


def build_ydl_opts(progress_hook: Callable | None, dest_dir: str | None = None) -> dict:
    opts = {
        "outtmpl": f"{dest_dir or DOWNLOAD_DIR}/%(title)s.%(ext)s",
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


def download_sync(url: str, progress_hook: Callable | None = None,
                  dest_dir: str | None = None) -> str:
    """Download a video/playlist URL to `dest_dir` (default DOWNLOAD_DIR = _workspace).
    Returns the output path of the (last) file. Blocking — callers run it on a worker
    thread (download/jobs, or the ingest worker for the resources copy)."""
    target = dest_dir or DOWNLOAD_DIR
    Path(target).mkdir(parents=True, exist_ok=True)
    opts = build_ydl_opts(progress_hook, dest_dir=target)
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        # Playlists return an "entries" list; report the last entry's file.
        if isinstance(info, dict) and info.get("entries"):
            entries = [e for e in info["entries"] if e]
            info = entries[-1] if entries else info
        return ydl.prepare_filename(info)


def list_playlist_entries(url: str, limit: int | None = None) -> list[dict]:
    """List a playlist's videos WITHOUT downloading (extract_flat) — lets the Java
    capture endpoint expand a playlist URL into individual queued captures, each
    downloaded/ingested one at a time by the existing capture pipeline. Raises
    ValueError if the URL has no entries (i.e. it's a single video, not a playlist).
    Returns [{"url": ..., "title": ...}, ...] in playlist order.

    `limit` (optional) caps how many entries yt-dlp extracts via `playlistend` —
    used by subscriptions.discover for channel polling, where only the most
    recent N uploads matter. Omitted by existing callers (e.g. /playlist/expand),
    which keep extracting the full playlist unchanged."""
    opts = {
        "extract_flat": "in_playlist",
        "skip_download": True,
        "quiet": True,
        "no_warnings": True,
    }
    if limit is not None:
        opts["playlistend"] = limit
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    entries = [e for e in (info or {}).get("entries", []) if e]
    if not entries:
        raise ValueError("not a playlist (no entries)")
    out = []
    for e in entries:
        video_url = e.get("url") or e.get("webpage_url")
        if not video_url:
            continue
        # extract_flat entries carry a bare video id as "url" for YouTube.
        if not video_url.startswith("http"):
            video_url = f"https://www.youtube.com/watch?v={video_url}"
        out.append({"url": video_url, "title": e.get("title") or video_url})
    return out


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
