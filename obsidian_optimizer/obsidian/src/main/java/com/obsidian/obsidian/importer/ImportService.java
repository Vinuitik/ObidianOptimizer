package com.obsidian.obsidian.importer;

import com.obsidian.obsidian.capture.CaptureController;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.sync.SyncService;
import com.obsidian.obsidian.vaults.ActiveVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Web-UI import. Everything lands under {@code _inbox/Imported/<source>-<date>/} (never the vault
 * root). Zips and loose .md are written to disk then indexed via one {@code FileRepository.triggerDeltaSync()};
 * .pdf/.txt/.docx go to the existing capture→ingest queue ({@code CaptureController}).
 */
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    public record Skipped(String path, String reason) {}
    public record Summary(int notes, int attachments, int queuedForIngest, List<String> folders, List<Skipped> skipped) {}

    private final ActiveVault activeVault;
    private final FileRepository repository;
    private final CaptureController capture;
    private final SyncService sync;
    private final ZipImporter.Limits limits;

    public ImportService(ActiveVault activeVault, FileRepository repository, CaptureController capture,
                         SyncService sync,
                         @Value("${import.max-zip-mb:500}") long maxZipMb,
                         @Value("${import.max-entries:20000}") int maxEntries,
                         @Value("${import.max-uncompressed-mb:2048}") long maxUncompressedMb) {
        this.activeVault = activeVault;
        this.repository = repository;
        this.capture = capture;
        this.sync = sync;
        this.limits = new ZipImporter.Limits(maxZipMb << 20, maxEntries, maxUncompressedMb << 20);
    }

    public Summary importFiles(List<MultipartFile> uploads) throws IOException {
        Path importsRoot = activeVault.root().resolve("_inbox").resolve("Imported");
        String date = LocalDate.now().toString();
        int notes = 0, attachments = 0, queued = 0;
        List<String> folders = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Path looseDir = null;

        for (MultipartFile f : uploads) {
            String name = baseName(f.getOriginalFilename());
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (f.isEmpty()) { skipped.add(new Skipped(name, "empty file")); continue; }
            switch (ext) {
                case "zip" -> {
                    Path tmp = Files.createTempFile("import-", ".zip");
                    try {
                        f.transferTo(tmp);
                        ZipImporter.Result r = ZipImporter.run(tmp, importsRoot, date, limits);
                        notes += r.notes();
                        attachments += r.attachments();
                        folders.add("_inbox/Imported/" + r.folder());
                        for (ZipImporter.Skip s : r.skipped()) skipped.add(new Skipped(name + ": " + s.path(), s.reason()));
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                }
                case "md", "markdown" -> {
                    looseDir = ensureLooseDir(looseDir, importsRoot, date, folders);
                    Files.write(looseDir.resolve(uniqueName(looseDir, name)), f.getBytes());
                    notes++;
                }
                case "pdf" -> {
                    try { capture.captureFileBytes(f.getBytes(), name, null); queued++; }
                    catch (Exception e) { skipped.add(new Skipped(name, "could not queue: " + e.getMessage())); }
                }
                case "txt" -> {
                    try {
                        capture.enqueueCapture(null, new String(f.getBytes(), StandardCharsets.UTF_8), stem(name));
                        queued++;
                    } catch (Exception e) { skipped.add(new Skipped(name, "could not queue: " + e.getMessage())); }
                }
                case "docx" -> {
                    try {
                        String text = DocxText.extract(f.getBytes());
                        if (text.isBlank()) { skipped.add(new Skipped(name, "no text found")); break; }
                        capture.enqueueCapture(null, text, stem(name));
                        queued++;
                    } catch (Exception e) { skipped.add(new Skipped(name, "could not read: " + e.getMessage())); }
                }
                default -> skipped.add(new Skipped(name, "unsupported file type (use .zip, .md, .txt, .pdf or .docx)"));
            }
        }
        if (notes > 0) {
            repository.triggerDeltaSync();
            // Drive sync_queue is only filled at boot (SyncService.onReady); files written straight
            // to disk would otherwise wait for the next restart before uploading.
            long vaultId = activeVault.id();
            java.util.concurrent.CompletableFuture.runAsync(() -> sync.initialScan(vaultId));
        }
        log.info("[import] notes={} attachments={} queued={} skipped={}", notes, attachments, queued, skipped.size());
        return new Summary(notes, attachments, queued, folders, skipped);
    }

    private static Path ensureLooseDir(Path cur, Path root, String date, List<String> folders) throws IOException {
        if (cur != null) return cur;
        Files.createDirectories(root);
        Path d = ZipImporter.uniqueDir(root, "files-" + date);
        folders.add("_inbox/Imported/" + d.getFileName());
        return d;
    }

    private static String uniqueName(Path dir, String name) {
        String n = name;
        for (int i = 2; Files.exists(dir.resolve(n)); i++) n = stem(name) + " (" + i + ")." + ext(name);
        return n;
    }

    private static String baseName(String n) {
        if (n == null) return "upload";
        String s = n.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        return s.isBlank() || s.equals("..") ? "upload" : s;
    }
    private static String stem(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }
    private static String ext(String n) { int d = n.lastIndexOf('.'); return d >= 0 ? n.substring(d + 1) : ""; }
}
