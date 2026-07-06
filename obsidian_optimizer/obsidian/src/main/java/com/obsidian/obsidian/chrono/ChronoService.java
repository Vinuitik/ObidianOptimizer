package com.obsidian.obsidian.chrono;

import com.obsidian.obsidian.cards.FlaggedCardRegenService;
import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.ml.ImageScanService;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.SyncQueueRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

@Component
public class ChronoService {

    private static final Logger log = LoggerFactory.getLogger(ChronoService.class);

    // Quiet mode: when false, the auto-triggers (startup + 2am) skip. Manual
    // POST /api/chrono/run still works. Lets you boot the app light for testing.
    @Value("${chrono.enabled:true}")
    private boolean chronoEnabled;

    // Own lane: the nightly pass is minutes of file I/O + rewrites, so the 2am
    // trigger runs here instead of holding a scheduler thread. Startup + manual
    // (POST /api/chrono/run) paths still call runAllJobs() directly.
    private final WorkerLane lane = new WorkerLane("chrono");

    private final FileMoverService   fileMover;
    private final BankruptcyService  bankruptcy;
    private final SpreadService      spread;
    private final SettingsRepository settingsRepo;
    private final FileRepository     fileRepo;
    private final NoteIndexRepository noteIndex;
    private final ImageScanService   imageScanService;
    private final SyncQueueRepository syncQueueRepo;
    private final FlaggedCardRegenService flaggedCardRegen;

    public ChronoService(FileMoverService fileMover,
                         BankruptcyService bankruptcy, SpreadService spread,
                         SettingsRepository settingsRepo, FileRepository fileRepo,
                         NoteIndexRepository noteIndex, ImageScanService imageScanService,
                         SyncQueueRepository syncQueueRepo,
                         FlaggedCardRegenService flaggedCardRegen) {
        this.fileMover        = fileMover;
        this.bankruptcy       = bankruptcy;
        this.spread           = spread;
        this.settingsRepo     = settingsRepo;
        this.fileRepo         = fileRepo;
        this.noteIndex        = noteIndex;
        this.imageScanService = imageScanService;
        this.syncQueueRepo    = syncQueueRepo;
        this.flaggedCardRegen = flaggedCardRegen;
    }

    public record ChronoResult(
        String date,
        int filesMoved,
        BankruptcyService.BankruptcyResult bankruptcy,
        SpreadService.SpreadResult spread
    ) {}

    @PostConstruct
    public void onStartup() {
        if (!chronoEnabled) {
            log.info("[ChronoService] chrono.enabled=false — skipping startup run (quiet mode).");
            return;
        }
        String lastRun = settingsRepo.getChronoLastRunDate();
        boolean neverRun = lastRun.isBlank();
        boolean stale    = !neverRun && LocalDate.parse(lastRun).isBefore(LocalDate.now());
        if (neverRun || stale) {
            log.info("[ChronoService] Not run today — running jobs on startup.");
            runAllJobs();
        } else {
            log.info("[ChronoService] Already ran today ({}). Skipping.", lastRun);
        }
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledRun() {
        if (!chronoEnabled) return;
        lane.trigger(() -> {
            log.info("[ChronoService] Scheduled 2am run triggered.");
            runAllJobs();
        });
    }

    public ChronoResult runAllJobs() {
        String vaultRoot   = settingsRepo.getVaultPath();
        List<Path> mdFiles = fileRepo.listMdPaths();

        int filesMoved = fileMover.run(vaultRoot);

        BankruptcyService.BankruptcyResult bankruptcyResult =
            bankruptcy.run(mdFiles, settingsRepo.getBankruptcyLimit(),
                           settingsRepo.getChronicNeglectDays());

        SpreadService.SpreadResult spreadResult =
            spread.run(mdFiles, settingsRepo.getMaxDailyReviews());

        // Refill user-flagged bad cards: one feedback-aware replacement per flag,
        // so notes whose garbage cards were quarantined don't stay thin. LLM-bound
        // (minutes) — fine here, the chrono lane is off the scheduler thread.
        FlaggedCardRegenService.RegenResult regen = flaggedCardRegen.run();

        fileRepo.triggerDeltaSync();

        // Detect changed files by comparing SHA-256 hashes. This catches both
        // external Obsidian edits AND the frontmatter rewrites the chrono jobs
        // above just made (spread/bankruptcy write directly to disk).
        // Each changed file gets its images re-queued AND is marked PENDING for
        // Drive sync.
        java.nio.file.Path vaultRootPath = java.nio.file.Paths.get(vaultRoot).toAbsolutePath().normalize();
        int changed = 0;
        for (Path mdPath : mdFiles) {
            String absPath = mdPath.toAbsolutePath().toString();
            try {
                String content = Files.readString(mdPath);
                String newHash = ContentHashing.sha256(content);
                String storedHash = noteIndex.getContentHash(absPath);
                if (!newHash.equals(storedHash)) {
                    imageScanService.registerImages(absPath, content);
                    String rel = vaultRootPath.relativize(mdPath.toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                    syncQueueRepo.markPending(rel, newHash);
                    changed++;
                }
            } catch (IOException e) {
                log.warn("[ChronoService] hash check skip {}: {}", absPath, e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("[ChronoService] {} changed note(s) detected — images re-queued, sync queued", changed);
        }

        String today = LocalDate.now().toString();
        settingsRepo.set("chronoLastRunDate", today);
        log.info("[ChronoService] Complete for {}. moved={} chronic={} bankrupt={} shifted={} cardsRefilled={}/{}",
            today, filesMoved, bankruptcyResult.chronicNeglected(),
            bankruptcyResult.declared(), spreadResult.moved(), regen.stored(), regen.replacementsRequested());

        return new ChronoResult(today, filesMoved, bankruptcyResult, spreadResult);
    }

    public String getLastRunDate() {
        return settingsRepo.getChronoLastRunDate();
    }
}
