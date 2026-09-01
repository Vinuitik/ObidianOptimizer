package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.common.IngestClient;
import com.obsidian.obsidian.common.PollingQueueWorker;
import com.obsidian.obsidian.common.WorkQueue;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fires the embedder's in-place ingest pipeline for video / audio / PDF embeds
 * sitting inside notes (INGEST_AGENT_ARCH). For each <code>![[resource.ext]]</code>
 * that has not yet been ingested (no <!-- ingest:… --> marker beneath it), POSTs
 * {@code {ref, note_path}} to the embedder, which synthesizes a note and injects
 * it directly below the embed. The embed is kept; the injected text is what the
 * chunker then embeds (the raw video/audio/PDF embed is invisible to chunking).
 *
 * Hooked into {@link ImageScanService#registerImages} — the single post-write
 * chokepoint — so every create/update/patch/rename/chrono/sync path is covered
 * for free. Calls are best-effort and off-thread: the embedder may be down or a
 * job may be minutes-long (whisper), and neither may ever block a note write.
 * The embedder de-dups concurrent jobs per (note, embed), so re-firing on every
 * keystroke-save until the marker lands is harmless.
 */
@Service
public class ResourceScanService {

    private static final Logger log = LoggerFactory.getLogger(ResourceScanService.class);

    // ![[clip.mp4]] / ![[lectures/clip.mp4]] — video, audio, or pdf only.
    // Aliased embeds (…|caption) are intentionally not matched (rare for A/V).
    private static final Pattern RESOURCE_EMBED = Pattern.compile(
        "!\\[\\[([^\\]|]+\\.(?:mp4|mkv|webm|mov|avi|mp3|m4a|wav|ogg|flac|pdf))\\]\\]",
        Pattern.CASE_INSENSITIVE);

    private final SettingsRepository settingsRepo;
    private final NoteIndexRepository noteIndexRepo;
    private final IngestClient ingestClient;   // the one gate to the embedder /ingest
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "resource-ingest-trigger");
        t.setDaemon(true);
        return t;
    });

    // Master switch for the whole ingest pipeline. false → notes are NOT gated on a
    // transcript (they embed/card from their text) and no ingest is fired.
    @Value("${ingest.enabled:true}")
    private boolean ingestEnabled;

    // Notes re-fired per retry cycle. The embedder de-dups + paces actual work, so
    // this just bounds how many we re-POST at once.
    @Value("${ingest.retry.batch-limit:50}")
    private int retryBatchLimit;

    // The embedder publishes synthesized notes BACK to the backend (:8084). During
    // the ~7-min boot (sync + image scan + chrono) Tomcat isn't bound yet, so firing
    // ingest then = the job finishes and the publish-back gets Connection refused.
    // Hold firing until ApplicationReadyEvent; the retry worker fires the backlog.
    private volatile boolean appReady = false;

    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        appReady = true;
    }

    private final PollingQueueWorker<String> pollingWorker;

    public ResourceScanService(SettingsRepository settingsRepo, NoteIndexRepository noteIndexRepo,
                               IngestClient ingestClient) {
        this.settingsRepo = settingsRepo;
        this.noteIndexRepo = noteIndexRepo;
        this.ingestClient = ingestClient;

        WorkQueue<String> queue = new WorkQueue<>() {
            @Override
            public List<String> claimBatch(int limit) {
                return noteIndexRepo.findIngestPending(limit);
            }

            // scan() unconditionally persists the ingest_pending gate itself, for
            // both the found-embeds and no-embeds-left cases — there is no separate
            // done/failed/deferred state to record here.
            @Override public void markDone(String item) {}
            @Override public void markFailed(String item, Exception error) {}
            @Override public void markDeferred(String item) {}
        };

        PollingQueueWorker.ItemProcessor<String> processor = absPath -> {
            try {
                scan(absPath, Files.readString(Paths.get(absPath)));
                return true;
            } catch (Exception e) {
                log.debug("[ResourceScanService] ingest retry read failed for {}: {}", absPath, e.getMessage());
                return false;
            }
        };

        PollingQueueWorker.BatchListener<String> listener = new PollingQueueWorker.BatchListener<>() {
            @Override
            public void onBatchClaimed(List<String> batch) {
                log.info("[ResourceScanService] re-firing ingest for {} pending note(s)", batch.size());
            }
        };

        // No WorkerLane: this tick already runs its actual re-ingest side effect
        // off-thread via scan() → trigger()'s own single-thread executor, and
        // single-pass (not continuousDrain) — a capped random batch per 5-minute
        // tick is the intended pacing, not a drain-to-empty.
        this.pollingWorker = new PollingQueueWorker<>(queue, processor,
            () -> retryBatchLimit, () -> ingestEnabled, false, null, listener);
    }

    /**
     * Trigger in-place ingest for any un-ingested resource embed in this note.
     * @param absNotePath absolute path of the note (as held by FileRepository)
     * @param content     the note's current markdown
     */
    public void scan(String absNotePath, String content) {
        if (!ingestEnabled) {
            // Pipeline disabled: clear the gate so the note isn't held back waiting
            // for a transcript that will never come — it embeds/cards from its text.
            noteIndexRepo.setIngestPending(absNotePath, false);
            return;
        }
        List<String> embeds = embedsNeedingIngest(content);
        // Readiness gate (synchronous, before any early return): the flag must be
        // CLEARED for clean notes too, not only set for pending ones — otherwise a
        // note never loses its pending status once its ingest marker lands.
        noteIndexRepo.setIngestPending(absNotePath, !embeds.isEmpty());
        if (embeds.isEmpty()) return;
        if (!appReady) return;   // backend web server not up yet — retry worker fires once ready
        String relPath = toRelative(absNotePath);
        if (relPath == null) return;           // note is outside the vault
        for (String embed : embeds) {
            trigger(relPath, embed);
        }
    }

    /**
     * Re-fires ingest for notes still flagged {@code ingest_pending}. The original
     * trigger is best-effort + fire-once, so a startup race (embedder not yet up) or
     * a transient failure would otherwise leave those notes waiting on the gate
     * FOREVER. This is the un-sticking the gate depends on. Paced: a capped random
     * batch per cycle; the embedder de-dups and drains its own queue at GPU/credit
     * speed, so the backlog clears over days. Disabled when ingest.enabled = false.
     */
    @Scheduled(fixedDelayString = "${ingest.retry.delay-ms:300000}",
               initialDelayString = "${ingest.retry.initial-delay-ms:180000}")
    public void retryPendingIngests() {
        pollingWorker.tick();
    }

    /** Resource embeds in {@code content} that do not yet carry their ingest
     *  marker — i.e. the ones still needing synthesis. Pure + static for test. */
    public static List<String> embedsNeedingIngest(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isEmpty()) return out;
        Matcher m = RESOURCE_EMBED.matcher(content);
        while (m.find()) {
            String embed = m.group(1);
            String base = embed.substring(embed.lastIndexOf('/') + 1);
            if (content.contains("<!-- ingest:" + base + " ")) continue;  // idempotent
            out.add(embed);
        }
        return out;
    }

    private void trigger(String relNotePath, String embed) {
        // Off-thread + best-effort: submitting must never block a note write, and a down
        // embedder is fine — the note stays ingest_pending and retryPendingIngests re-fires.
        pool.submit(() -> {
            IngestClient.Result r = ingestClient.submitInPlace(embed, relNotePath);
            if (r.ok()) {
                log.info("[ResourceScanService] queued ingest of {} in {}", embed, relNotePath);
            } else {
                log.debug("[ResourceScanService] ingest {} not accepted ({}) — will retry",
                    embed, r.status());
            }
        });
    }

    private String toRelative(String absNotePath) {
        try {
            Path vault = Paths.get(settingsRepo.getVaultPath()).toAbsolutePath().normalize();
            Path note  = Paths.get(absNotePath).toAbsolutePath().normalize();
            if (!note.startsWith(vault)) return null;
            return vault.relativize(note).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return null;
        }
    }
}
