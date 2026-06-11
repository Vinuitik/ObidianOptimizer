# Media Domain Flows

Files: MediaController.java

---

## POST /upload

`MediaController.uploadEndpoint(MultipartFile file, String filename)`:
1. Reject filenames with `/`, `\`, `..` → 400
2. `subdirFor(filename)` → extension-based subdir string
3. `Files.createDirectories(vaultPath/resources/<subdir>/)`
4. `file.transferTo(targetDir/filename)`
5. Return `{ filename, url: "/api/images/<filename>" }`

Max upload size: `application.properties spring.servlet.multipart.max-file-size=100MB`

---

## GET /images/{filename}

`MediaController.getImage(filename)` → iterates `SEARCH_SUBDIRS = ["images","videos","pdf","audio","files"]`  
For each subdir: resolves `vaultPath/resources/<subdir>/filename` → path-traversal guard (`startsWith(resourcesRoot)`) → if exists → `mimeFor(ext)` → `ResponseEntity<Resource>` inline  
Returns 404 if not found in any subdir.

Filenames with `/`, `\`, `..` rejected with 400 before the loop.

---

## subdirFor / mimeFor

| Extension group | subdir | MIME |
|---|---|---|
| `.png .jpg .jpeg .gif .webp .svg` | `images` | `image/*` |
| `.mp4 .mov .mkv .webm .avi` | `videos` | `video/*` |
| `.mp3 .wav .ogg .m4a .flac` | `audio` | `audio/*` |
| `.pdf` | `pdf` | `application/pdf` |
| default | `files` | `application/octet-stream` |

To add extension: `MediaController.*_EXTS` set + `subdirFor()` routing + `mimeFor()` entry

---

## Technology Notes

- **Multi-directory serving**: filenames without path separators can live in any subdir. Upload subdir is determined at write time via `subdirFor()`; read iterates `SEARCH_SUBDIRS` in order.
- **No deduplication**: uploading the same filename twice overwrites the first. Filename collision avoidance is the frontend's responsibility (`generateFilename` appends timestamp + random hex).
- **Path traversal guard**: `resolved.startsWith(resourcesRoot)` checked before read. Filenames with `/`, `\`, `..` rejected up front with 400.

---

## Change Index

| Thing to change | Where |
|---|---|
| Accepted extensions + subdir routing | `MediaController.*_EXTS` sets + `subdirFor()` |
| MIME types | `MediaController.mimeFor()` |
| Search order for serving | `MediaController.SEARCH_SUBDIRS` |
| Upload size limit | `application.properties spring.servlet.multipart.max-*-size` |
