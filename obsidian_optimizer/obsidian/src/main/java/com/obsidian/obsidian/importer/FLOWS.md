# Import Flows

Files: ImportController.java, ImportService.java, ZipImporter.java, NotionNormalizer.java, ImportPaths.java, DocxText.java, ImportRejectedException.java (+ frontend `pages/ImportPanel.jsx`, `api/importNotes.js`)

Package is `importer` (not `import` — reserved word in Java).

## Zip (Notion export or Obsidian/markdown folder)

Settings ▸ Import → `importNotes(files)` → `POST /api/import` (nginx `location = /api/import` → backend `import`) → `ImportController.importNotes()` → `ImportService.importFiles()` → `.zip`: `transferTo` temp file → `ZipImporter.run()`:
1. size > cap → 413 · entries > cap → 413 · every entry through `ImportPaths.sanitize()` (any `..` / absolute / drive letter → whole zip rejected, nothing written) · junk skipped (`__MACOSX`, `.obsidian`, dotfiles, nested `.zip`)
2. `NotionNormalizer.looksLikeNotion()` (an `.md` with ` <32hex>` suffix) → source `notion`, else `obsidian`
3. Notion: `planRenames()` (strip ids, `.csv`→`.md`, collisions ` (2)`), `*_all.csv` skipped; each `.md` → `rewriteLinks()` (`[T](Page%20<id>.md)` → `[[Page]]`/`[[Page|T]]`, attachments → relative renamed path); `.csv` → `# Title` + `csvToMarkdown()`
4. Plain: copied verbatim (folders + attachments kept)
5. Written to `<vault>/_inbox/Imported/<source>-<yyyy-MM-dd>[ (n)]/` (never vault root); failure mid-write deletes the folder
→ `FileRepository.triggerDeltaSync()` once → notes indexed like any externally-added file.
  → `SyncService.initialScan(vaultId)` async → new files marked PENDING in sync_queue so Drive uploads them without a restart.
To change target folder: `ImportService.importFiles()` (`importsRoot`). To change junk rules: `ImportPaths.junkReason()`.

## Loose files

`.md` → `_inbox/Imported/files-<date>/` + delta sync. `.pdf` → `CaptureController.captureFileBytes()` (durable capture queue → ingest). `.txt` / `.docx` (text via `DocxText.extract()`) → `CaptureController.enqueueCapture(null, text, title)` (the text-capture path). Others → skipped with reason.
To support another type: `ImportService.importFiles()` switch (+ `CaptureController.classifyFile` if ingest must accept it).

## Config (env → property)

| Env | Property | Default |
|---|---|---|
| `IMPORT_MAX_ZIP_MB` | `import.max-zip-mb` (also drives `spring.servlet.multipart.max-*-size`) | 500 |
| `IMPORT_MAX_ENTRIES` | `import.max-entries` | 20000 |
| `IMPORT_MAX_UNCOMPRESSED_MB` | `import.max-uncompressed-mb` | 2048 |

nginx `client_max_body_size 512m` in `frontend/nginx.conf.template` must be ≥ `IMPORT_MAX_ZIP_MB`.

## Technology Notes

- **Memory**: the upload spills to a temp file and is read with `ZipFile` (random access, central directory only in heap). Each `.md`/`.csv` ≤ 10 MB (`ZipImporter.MAX_IN_MEMORY_MD`) is read fully into heap for link rewrite; bigger ones and all attachments stream in 64 KB chunks. Loose `.pdf/.txt/.docx` use `MultipartFile.getBytes()` (≤ multipart cap in heap). Peak ≈ largest single md + buffers.
- **Zip bombs**: declared sizes are summed up front, and bytes are counted again while copying (headers can lie) against `max-uncompressed`.
- **Synchronous**: the HTTP request does unzip + index; nginx read timeout 900 s. Browser tab must stay open; no resumable upload; progress is indeterminate (XHR would bypass `installVaultFetch`'s `X-Vault-Id`).
- **Notion wikilinks are by name only** (`[[Name]]`); duplicate page names in different folders resolve ambiguously in Obsidian-style linking.
- **Attachments stay beside the notes** in `_inbox/Imported/…`, not in `resources/` — they render only if the editor resolves relative paths [unverified in browser].
- **Notion nested exports** (`Export-…-Part-1.zip` inside the zip) are skipped with a reason, not unwrapped.
- **.docx** text extraction is paragraph-only regex on `word/document.xml` (no tables/images/lists).
- **Raising `multipart.max-*-size` to 500 MB is global** — every multipart endpoint accepts it, not just import.
- **Drive sync**: files written directly (not via `FileRepository.createNote`) are not `markPending` in the sync queue — [verify they upload; startup delta sync is the only index hook].

## Change Index

| Thing | Where |
|---|---|
| Size/entry limits | env `IMPORT_MAX_*`, `ImportService` constructor `@Value` |
| Zip-slip / junk rules | `ImportPaths.sanitize()`, `ImportPaths.junkReason()` |
| Notion rename/link/CSV rules | `NotionNormalizer.planRenames()`, `rewriteLinks()`, `csvToMarkdown()` |
| Destination folder naming | `ZipImporter.run()` (`source-date`), `ZipImporter.uniqueDir()` |
| Raw-file routing | `ImportService.importFiles()` switch |
| HTTP errors | `ImportController`, `ImportRejectedException.status()` |
| Upload body size / timeout | `frontend/nginx.conf.template` `location = /api/import` |
| UI | `frontend/src/pages/ImportPanel.jsx`, section list in `SettingsPage.jsx` `SECTIONS` |
