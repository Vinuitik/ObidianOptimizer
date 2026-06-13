package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "resource-ingest-trigger");
        t.setDaemon(true);
        return t;
    });

    @Value("${embedder.url:http://localhost:8000}")
    private String embedderUrl;

    public ResourceScanService(SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    /**
     * Trigger in-place ingest for any un-ingested resource embed in this note.
     * @param absNotePath absolute path of the note (as held by FileRepository)
     * @param content     the note's current markdown
     */
    public void scan(String absNotePath, String content) {
        List<String> embeds = embedsNeedingIngest(content);
        if (embeds.isEmpty()) return;
        String relPath = toRelative(absNotePath);
        if (relPath == null) return;           // note is outside the vault
        for (String embed : embeds) {
            trigger(relPath, embed);
        }
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
        pool.submit(() -> {
            try {
                String body = objectMapper.writeValueAsString(
                    Map.of("ref", embed, "note_path", relNotePath));
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(embedderUrl + "/ingest"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    log.info("[ResourceScanService] queued ingest of {} in {}", embed, relNotePath);
                } else {
                    log.warn("[ResourceScanService] ingest {} -> {}: {}", embed,
                        r.statusCode(), r.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // embedder down / slow — a later save or restart re-fires
                log.debug("[ResourceScanService] trigger failed for {}: {}", embed, e.toString());
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
