package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.common.OutboxRepository;
import com.obsidian.obsidian.common.RabbitQueueConfig;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
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
    private final OutboxRepository outboxRepo;

    public ImageScanService(PendingImageJobRepository jobRepo, NoteIndexRepository noteIndexRepo,
                            ResourceScanService resourceScanService, OutboxRepository outboxRepo) {
        this.jobRepo             = jobRepo;
        this.noteIndexRepo       = noteIndexRepo;
        this.resourceScanService = resourceScanService;
        this.outboxRepo          = outboxRepo;
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
     *
     * <p>{@code @Transactional}: the content_hash/ingest_pending writes and the outbox
     * row below must commit together, or a crash between them would either lose the
     * "needs embedding" signal or fire one for a write that never happened. The old
     * NoteEmbeddingWorker poll (now a much-slower safety net, see
     * {@code embedding.scan.delay-ms}) still finds any note this misses.
     */
    @Transactional
    public int registerImages(String notePath, String content) {
        List<String> refs = extractImageRefs(content);
        for (String ref : refs) {
            PendingImageJobRepository.UpsertResult result = jobRepo.upsertPending(notePath, ref);
            // Only publish when the row actually needs captioning — an already-DONE
            // image (re-registered because the note's OTHER content changed) has
            // nothing for the listener to do. Same chokepoint, fourth job: the
            // outbox+RabbitMQ fast path for image captioning (Phase 5).
            if (result.needsProcessing()) {
                outboxRepo.enqueue(ImageCaptionQueueConfig.IMAGE_CAPTION_QUEUE, Map.of("jobId", result.id()));
            }
        }
        String hash = ContentHashing.sha256(content);
        String bodyHash = ContentHashing.sha256(MarkdownPreprocessor.stripFrontmatter(content));
        noteIndexRepo.updateContentHash(notePath, hash, bodyHash);
        // Same chokepoint, second job: trigger in-place ingest of any A/V or
        // PDF embeds in this note (best-effort, off-thread — never blocks).
        resourceScanService.scan(notePath, content);
        // Third job, same chokepoint: the outbox+RabbitMQ fast path for embedding (see
        // QUEUE_UNIFICATION_PLAN.md Phase 3). Checked AFTER scan() so ingest_pending
        // reflects its final value — a note still awaiting a transcript must not fire
        // an embed message yet.
        if (noteIndexRepo.needsEmbedding(notePath)) {
            outboxRepo.enqueue(RabbitQueueConfig.EMBED_QUEUE, Map.of("notePath", notePath));
        }
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

}
