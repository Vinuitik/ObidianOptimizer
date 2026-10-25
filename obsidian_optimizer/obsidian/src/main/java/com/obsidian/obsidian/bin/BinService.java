package com.obsidian.obsidian.bin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.obsidian.obsidian.feed.FeedRepository;
import com.obsidian.obsidian.feed.FeedRepository.FeedItem;
import com.obsidian.obsidian.feed.FeedService;
import com.obsidian.obsidian.feed.SourceRepository;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import com.obsidian.obsidian.vaults.ActiveVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Aggregator over every soft-deleted thing in the ACTIVE vault: files (bin_entries), tracks/track items
 * (deleted_at), subscriptions (deleted_at), feed items (status 'binned'), plus legacy untracked
 * {@code _trash/} entries ("unknown"). Restore is full + recursive. See bin/FLOWS.md.
 */
@Service
public class BinService {

    private static final Logger log = LoggerFactory.getLogger(BinService.class);

    public static final List<String> KINDS =
        List.of("note", "folder", "file", "feed", "track", "track_item", "subscription", "unknown");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BinItem(String kind, String id, String label, String originalPath, Instant deletedAt) {}

    private final BinRepository binRepo;
    private final FileRepository fileRepo;
    private final TrackRepository trackRepo;
    private final SourceRepository sourceRepo;
    private final FeedRepository feedRepo;
    private final FeedService feedService;
    private final ActiveVault activeVault;

    @Value("${BIN_RETENTION_DAYS:30}")
    private int retentionDays;

    public BinService(BinRepository binRepo, FileRepository fileRepo, TrackRepository trackRepo,
                      SourceRepository sourceRepo, FeedRepository feedRepo, FeedService feedService,
                      ActiveVault activeVault) {
        this.binRepo = binRepo;
        this.fileRepo = fileRepo;
        this.trackRepo = trackRepo;
        this.sourceRepo = sourceRepo;
        this.feedRepo = feedRepo;
        this.feedService = feedService;
        this.activeVault = activeVault;
    }

    // ── List ─────────────────────────────────────────────────────────────────

    public Map<String, List<BinItem>> list() {
        long vid = activeVault.id();
        Map<String, List<BinItem>> out = new LinkedHashMap<>();
        for (String k : KINDS) out.put(k, new ArrayList<>());

        Set<String> known = new HashSet<>();
        for (BinRepository.Entry e : binRepo.list(vid)) {
            known.add(e.trashPath());
            out.get(e.kind()).add(new BinItem(e.kind(), String.valueOf(e.id()), e.label(), e.originalPath(), e.deletedAt()));
        }
        for (FeedItem f : feedRepo.list(vid, "binned", 500)) {
            out.get("feed").add(new BinItem("feed", String.valueOf(f.id()),
                f.title() == null || f.title().isBlank() ? f.itemUrl() : f.title(), null, f.decidedAt()));
        }
        for (TrackRepository.BinTrack t : trackRepo.listDeletedTracks(vid)) {
            out.get("track").add(new BinItem("track", String.valueOf(t.id()), t.title(), null, t.deletedAt()));
        }
        for (TrackRepository.BinTrackItem i : trackRepo.listDeletedItems(vid)) {
            out.get("track_item").add(new BinItem("track_item", String.valueOf(i.id()),
                i.trackTitle() + " › " + i.title(), null, i.deletedAt()));
        }
        for (SourceRepository.BinSource s : sourceRepo.listDeleted(vid)) {
            out.get("subscription").add(new BinItem("subscription", String.valueOf(s.id()),
                s.title() == null ? s.url() : s.title(), s.url(), s.deletedAt()));
        }
        for (Path p : legacyTrashEntries(activeVault.root(), known)) {
            Instant mtime;
            try { mtime = Files.getLastModifiedTime(p).toInstant(); } catch (IOException e) { mtime = null; }
            out.get("unknown").add(new BinItem("unknown", p.getFileName().toString(),
                p.getFileName().toString(), null, mtime));
        }
        return out;
    }

    /** Top-level _trash children that no bin_entries row points at (pre-bin deletions: origin lost). */
    private List<Path> legacyTrashEntries(Path root, Set<String> knownTrashPaths) {
        Path trash = root.resolve("_trash");
        if (!Files.isDirectory(trash)) return List.of();
        try (Stream<Path> s = Files.list(trash)) {
            return s.filter(p -> !knownTrashPaths.contains("_trash/" + p.getFileName()))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[Bin] cannot list {}: {}", trash, e.getMessage());
            return List.of();
        }
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    /** Returns the restored vault-relative path for file kinds, else null. */
    public String restore(String kind, String id) {
        long vid = activeVault.id();
        switch (kind) {
            case "note", "folder", "file" -> {
                BinRepository.Entry e = fileEntry(vid, kind, id);
                try {
                    String restored = fileRepo.restoreFromTrash(e.trashPath(), e.originalPath());
                    binRepo.delete(e.id());
                    return restored;
                } catch (java.nio.file.NoSuchFileException gone) {
                    binRepo.delete(e.id());   // trash copy vanished (manual cleanup): entry is stale
                    throw new ResponseStatusException(HttpStatus.GONE, "trashed copy no longer exists");
                } catch (IOException io) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, io.getMessage());
                }
            }
            case "unknown" -> {
                requireSimpleName(id);
                try {
                    return fileRepo.restoreFromTrash("_trash/" + id, "_inbox/" + id);
                } catch (java.nio.file.NoSuchFileException gone) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not in bin");
                } catch (IOException io) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, io.getMessage());
                }
            }
            case "track" -> { if (!trackRepo.restoreTrack(vid, num(id))) throw notFound(); }
            case "track_item" -> { if (!trackRepo.restoreItem(vid, num(id))) throw notFound(); }
            case "subscription" -> {
                try {
                    if (!sourceRepo.restore(vid, num(id))) throw notFound();
                } catch (IllegalStateException conflict) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
                }
            }
            case "feed" -> {
                FeedItem f = feedRepo.get(vid, num(id));
                if (f == null || !"binned".equals(f.status())) throw notFound();
                feedService.restore(vid, f.id());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown kind: " + kind);
        }
        return null;
    }

    // ── Delete forever ───────────────────────────────────────────────────────

    public void purge(String kind, String id) {
        long vid = activeVault.id();
        switch (kind) {
            case "note", "folder", "file" -> {
                BinRepository.Entry e = fileEntry(vid, kind, id);
                deleteTrashPath(activeVault.root(), e.trashPath());
                binRepo.delete(e.id());
            }
            case "unknown" -> {
                requireSimpleName(id);
                if (!deleteTrashPath(activeVault.root(), "_trash/" + id)) throw notFound();
            }
            case "track" -> { if (!trackRepo.purgeTrack(vid, num(id))) throw notFound(); }
            case "track_item" -> { if (!trackRepo.purgeItem(vid, num(id))) throw notFound(); }
            case "subscription" -> { if (!sourceRepo.purge(vid, num(id))) throw notFound(); }
            case "feed" -> { if (!feedRepo.dismissBinned(vid, num(id))) throw notFound(); }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown kind: " + kind);
        }
    }

    /** Empty the active vault's whole bin (including legacy _trash contents). Returns items removed. */
    public int emptyAll() {
        long vid = activeVault.id();
        Path root = activeVault.root();
        int n = 0;
        for (BinRepository.Entry e : binRepo.list(vid)) {
            deleteTrashPath(root, e.trashPath());
            binRepo.delete(e.id());
            n++;
        }
        for (Path p : legacyTrashEntries(root, Set.of())) {
            if (deleteTrashPath(root, "_trash/" + p.getFileName())) n++;
        }
        for (TrackRepository.BinTrack t : trackRepo.listDeletedTracks(vid)) { trackRepo.purgeTrack(vid, t.id()); n++; }
        for (TrackRepository.BinTrackItem i : trackRepo.listDeletedItems(vid)) { trackRepo.purgeItem(vid, i.id()); n++; }
        for (SourceRepository.BinSource s : sourceRepo.listDeleted(vid)) { sourceRepo.purge(vid, s.id()); n++; }
        n += feedRepo.dismissAllBinned(vid);
        return n;
    }

    // ── Retention ────────────────────────────────────────────────────────────

    @Scheduled(cron = "${bin.purge.cron:0 30 3 * * *}")
    public void purgeExpiredNightly() {
        try {
            int n = purgeExpired(Instant.now().minus(Duration.ofDays(retentionDays)));
            if (n > 0) log.info("[Bin] retention purge removed {} item(s) older than {}d", n, retentionDays);
        } catch (RuntimeException e) {
            log.error("[Bin] retention purge failed", e);
        }
    }

    /** Hard-delete everything binned before {@code cutoff}, across all vaults. Legacy untracked trash is never auto-purged. */
    public int purgeExpired(Instant cutoff) {
        int n = 0;
        for (BinRepository.Entry e : binRepo.listBefore(cutoff)) {
            try {
                deleteTrashPath(activeVault.rootFor(e.vaultId()), e.trashPath());
            } catch (RuntimeException ex) {
                log.warn("[Bin] vault {} file delete failed, dropping entry {}: {}", e.vaultId(), e.id(), ex.getMessage());
            }
            binRepo.delete(e.id());
            n++;
        }
        n += trackRepo.purgeDeletedBefore(cutoff);
        n += sourceRepo.purgeDeletedBefore(cutoff);
        n += feedRepo.dismissBinnedBefore(cutoff);
        return n;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BinRepository.Entry fileEntry(long vid, String kind, String id) {
        BinRepository.Entry e = binRepo.get(vid, num(id));
        if (e == null || !e.kind().equals(kind)) throw notFound();
        return e;
    }

    /** Delete a file/dir under {@code <root>/_trash} (guarded); true if something was removed. */
    private boolean deleteTrashPath(Path root, String trashRel) {
        Path trash = root.resolve("_trash").toAbsolutePath().normalize();
        Path target = root.resolve(trashRel).toAbsolutePath().normalize();
        if (!target.startsWith(trash) || target.equals(trash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path outside _trash");
        }
        if (!Files.exists(target)) return false;
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            return true;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "delete failed: " + e.getMessage());
        }
    }

    private static void requireSimpleName(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id");
        }
    }

    private static long num(String id) {
        try { return Long.parseLong(id); }
        catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id"); }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "not in bin");
    }
}
