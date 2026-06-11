# Video & Audio Manager Architecture

## Overview
A pipeline to capture, pre-download, AI-process, and sync video/audio content to the mobile app for offline learning (e.g., during runs or commutes). This removes the friction of consuming educational content and ensures it integrates directly into the note-taking and spaced repetition system.

## The 4-Step Pipeline

### 1. Capture (Browser Extension)
*   **Action**: While browsing YouTube or educational sites, the user clicks a "Queue for Offline" button in our Browser Extension.
*   **Result**: The extension sends the URL to the Laptop's Spring Boot API (`POST /api/media/queue`).
*   **Database**: Logged in a `media_queue` table with status `PENDING`.

### 2. Download via Sister App (`VideoManager`)
Instead of duplicating `yt-dlp` logic in `ObsidianOptimizer`, we will integrate with your existing `VideoManager` application via HTTP.
*   **API Call**: The Spring Boot backend reads `PENDING` queue items and issues a `POST http://localhost:8000/api/v1/download {url}` to the VideoManager FastAPI backend.
*   **Polling/Status**: Spring Boot will poll `GET /api/v1/jobs/{job_id}` on the VideoManager to track progress.
*   **Outcome**: The `VideoManager` successfully downloads the video (falling back to its own Agent ReAct loop if authentication or scraping gets complex) and saves the `.mp4` file and a thumbnail in its `VIDEOS_DIR` folder.

### 3. Transcription & Notes (Obsidian Optimizer Laptop)
Once the VideoManager reports `job.status="done"`, Spring Boot resumes control:
*   **Audio Extraction**: Spring Boot grabs the file from VideoManager (or accesses the shared Volume if co-located) and uses `ffmpeg` to strip a lightweight audio track.
*   **Transcription**: The audio track is passed to a local Whisper model (via Ollama or the `host-wrapper`).
*   **Note Generation**: Passes the transcript to an LLM to generate structured markdown notes (Summary, Key Concepts, Action Items).
*   **Storage**: Saves the AI notes as a standard `.md` file linking to the media file.

### 4. Sync Engine (Google Drive)
*   **Notes**: The Markdown files are bundled in the standard SQLite "Master State" zip.
*   **Large Media Assets**: The laptop mirrors the audio/video file directly to an Obsidian `Media_Sync/` folder on Google Drive as standalone files. 
*   **Mobile App**: The mobile app downloads these files selectively when on Wi-Fi, pre-filling a split-screen Learning Player interface (video/audio on top, editable AI markdown on bottom).

## Technical Requirements to Add Later
*   `yt-dlp` executable available to the backend host.
*   Media player component in React Native (e.g., `expo-av` and `expo-video`).
*   Google Drive sync logic must handle standalone binary files, not just the database zip.