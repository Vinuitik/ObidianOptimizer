package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.notes.NoteIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImageScanService {

    private static final Logger log = LoggerFactory.getLogger(ImageScanService.class);

    // Matches ![[image.ext]] and ![alt](path) where ext is a known image type
    private static final Pattern WIKI_IMAGE = Pattern.compile(
        "!\\[\\[([^\\]]+\\.(?:png|jpg|jpeg|gif|webp|svg|bmp))\\]\\]",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_IMAGE = Pattern.compile(
        "!\\[[^\\]]*\\]\\(([^)]+\\.(?:png|jpg|jpeg|gif|webp|svg|bmp))\\)",
        Pattern.CASE_INSENSITIVE);

    private final PendingImageJobRepository jobRepo;
    private final NoteIndexRepository noteIndexRepo;
    private final ResourceScanService resourceScanService;

    public ImageScanService(PendingImageJobRepository jobRepo, NoteIndexRepository noteIndexRepo,
                            ResourceScanService resourceScanService) {
        this.jobRepo             = jobRepo;
        this.noteIndexRepo       = noteIndexRepo;
        this.resourceScanService = resourceScanService;
    }

    /** Called after FileRepository.init() — walks all indexed notes and queues unprocessed images. */
    public void scanAll(List<File> mdFiles) {
        int queued = 0;
        for (File f : mdFiles) {
            try {
                String content = Files.readString(f.toPath());
                queued += registerImages(f.getAbsolutePath(), content);
            } catch (IOException e) {
                log.warn("[ImageScanService.scanAll] skip {}: {}", f.getAbsolutePath(), e.getMessage());
            }
        }
        log.info("[ImageScanService.scanAll] queued {} image job(s) across {} notes", queued, mdFiles.size());
    }

    /**
     * Extracts image refs from content and upserts PENDING rows for any not already DONE.
     * Also updates the content_hash in the notes table.
     * Returns the count of newly queued images.
     */
    public int registerImages(String notePath, String content) {
        List<String> refs = extractImageRefs(content);
        for (String ref : refs) {
            jobRepo.upsertPending(notePath, ref);
        }
        String hash = sha256(content);
        noteIndexRepo.updateContentHash(notePath, hash);
        // Same chokepoint, second job: trigger in-place ingest of any A/V or
        // PDF embeds in this note (best-effort, off-thread — never blocks).
        resourceScanService.scan(notePath, content);
        return refs.size();
    }

    public static List<String> extractImageRefs(String content) {
        java.util.List<String> refs = new java.util.ArrayList<>();
        Matcher m1 = WIKI_IMAGE.matcher(content);
        while (m1.find()) refs.add(m1.group(1));
        Matcher m2 = MD_IMAGE.matcher(content);
        while (m2.find()) refs.add(m2.group(1));
        return refs;
    }

    public static String sha256(String content) {
        return sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
