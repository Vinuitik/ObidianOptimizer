# Bin (unified soft-delete + restore)

Files: BinController.java, BinService.java, BinRepository.java, BinIT.java (test); readers/writers: ../notes/FileRepository.java, ../tracks/TrackRepository.java, ../feed/SourceRepository.java, ../feed/FeedRepository.java, ../feed/FeedService.java

## API (proxy-prefixed `/api`)
```
GET    /api/bin                  → {note[],folder[],file[],feed[],track[],track_item[],subscription[],unknown[]}
                                   item = {kind, id (string), label, originalPath? (note/folder/file/subscription url), deletedAt}
POST   /api/bin/restore {kind,id}→ 200 {restored:true, path?}   404 not in bin | 409 subscription URL re-subscribed | 410 trash copy gone
DELETE /api/bin/{kind}/{id}      → 204 forever
DELETE /api/bin                  → {removed:n}  empty everything (active vault)
```
To change: `BinController`, `BinService.list()/restore()/purge()/emptyAll()`.

## Delete (writes)
```
NotesController.deleteNote / InboxController / CaptureIngestWorker
  → FileRepository.softDeleteNote|File|Folder → move into <vault>/_trash/ (flattened)
  → BinRepository.record(vault, kind, trash_path, original_path, label)   (paths vault-relative)
TrackController.delete    → TrackRepository.delete   → tracks.deleted_at + live items.deleted_at (same ts)
TrackController.deleteItem→ TrackRepository.deleteItem → track_items.deleted_at
FeedController.deleteSource → SourceRepository.delete → sources.deleted_at (feed items hidden by join)
FeedController items/{id}/bin → FeedService.bin → feed_items.status='binned' (unchanged)
```
Folder delete = ONE `bin_entries` row (restores as a unit). `parent_id` column exists, unused (reserved).

## Restore (always full + recursive)
```
file kinds → BinService.restore → FileRepository.restoreFromTrash(trash_path, original_path)
   → mkdirs parent → " (restored[ n])" suffix if taken → Files.move
   → for every .md inside (skipping EXCLUDED_DIRS like _sources): reindexAfterExternalWrite
        → NoteIndexRepository.upsert + links + images + SyncQueueRepository.markPending
   → delete bin_entries row
unknown (legacy _trash/ child with no entry) → same, original = _inbox/<name>
track → TrackRepository.restoreTrack → track + items with the SAME deleted_at (items deleted earlier stay in bin as track_item); schedule rows were never removed
track_item → restoreItem (only if its track is alive)
subscription → SourceRepository.restore → feed items reappear (join); 409 if URL re-subscribed
feed → FeedService.restore → pending
```

## Forever + retention
`DELETE` → `BinService.purge` → file kinds: recursive delete under `_trash/` (path-guarded) + row; track/subscription: hard row delete (cascades items/feed_items); feed: `status='dismissed'` (keeps `item_url` dedup so the poller can't re-add it).
Nightly `BinService.purgeExpiredNightly()` (cron `bin.purge.cron`, default `0 30 3 * * *`; `BIN_RETENTION_DAYS`, default 30) → `purgeExpired(cutoff)` across ALL vaults (`ActiveVault.rootFor`). Legacy "unknown" trash is NEVER auto-purged (no deletion time).
Drive tombstones were already sent at soft-delete time; restore re-queues via markPending.

## Technology Notes
- Filesystem move and DB are NOT atomic: crash between `Files.move` and the DB write leaves a trashed file without an entry (shows as "unknown" - restorable to `_inbox/`) or a restored file un-indexed until the next startup disk sync.
- Legacy `_trash/` content from before the Bin has no origin path; mtime shown as deletedAt is the file's last edit, not its deletion time.
- `_trash/` is flattened: name clashes get `_<millis>` suffixes; the entry stores the real trash path, so restore is exact.
- Restore into a changed tree: missing parent dirs are recreated; taken names get a ` (restored)` suffix (never overwrites).
- Bin acts on the ACTIVE vault only (file ops use the active root); only the nightly purge is cross-vault.
- Soft-deleted tracks/sources stay in their tables: every new query on `tracks`, `track_items`, `sources` MUST filter `deleted_at IS NULL` or deleted rows leak back.
- Retention purge, restore, and forever-delete are not transactional across rows; a failure mid-way is retried by the next nightly run.
- `track_item` is an extra kind beyond the six UI segments (individually deleted items would otherwise be unrecoverable).

## Change Index
| Thing | Where |
|---|---|
| Retention days / cron | `BIN_RETENTION_DAYS`, `bin.purge.cron`, `BinService.purgeExpiredNightly()` |
| bin_entries DDL | `BinRepository.initSchema()` |
| Record file deletions | `FileRepository.softDeleteNote/File/Folder` → `BinRepository.record` |
| File restore / suffix rule / reindex | `FileRepository.restoreFromTrash()` |
| Track soft delete / restore / purge | `TrackRepository.delete/restoreTrack/restoreItem/purgeTrack/purgeItem` |
| Subscription soft delete / restore / purge | `SourceRepository.delete/restore/purge` |
| Feed forever (dismissed) | `FeedRepository.dismissBinned/dismissAllBinned/dismissBinnedBefore` |
| Legacy trash listing | `BinService.legacyTrashEntries()` |
| Endpoints | `BinController` |
