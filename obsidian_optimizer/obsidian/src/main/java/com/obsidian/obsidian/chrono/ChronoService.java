package com.obsidian.obsidian.chrono;

import com.obsidian.obsidian.ml.ImageScanService;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final FileMoverService   fileMover;
    private final FileCheckerService fileChecker;
    private final BankruptcyService  bankruptcy;
    private final SpreadService      spread;
    private final SettingsRepository settingsRepo;
    private final FileRepository     fileRepo;
    private final NoteIndexRepository noteIndex;
    private final ImageScanService   imageScanService;

    public ChronoService(FileMoverService fileMover, FileCheckerService fileChecker,
                         BankruptcyService bankruptcy, SpreadService spread,
                         SettingsRepository settingsRepo, FileRepository fileRepo,
                         NoteIndexRepository noteIndex, ImageScanService imageScanService) {
        this.fileMover        = fileMover;
        this.fileChecker      = fileChecker;
        this.bankruptcy       = bankruptcy;
        this.spread           = spread;
        this.settingsRepo     = settingsRepo;
        this.fileRepo         = fileRepo;
        this.noteIndex        = noteIndex;
        this.imageScanService = imageScanService;
    }

    public record ChronoResult(
        String date,
        int filesMoved,
        int filesFixed,
        BankruptcyService.BankruptcyResult bankruptcy,
        SpreadService.SpreadResult spread
    ) {}

    @PostConstruct
    public void onStartup() {
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

    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledRun() {
        log.info("[ChronoService] Scheduled 2am run triggered.");
        runAllJobs();
    }

    public ChronoResult runAllJobs() {
        String vaultRoot    = settingsRepo.getVaultPath();
        List<Path> mdFiles  = fileRepo.listMdPaths();

        int filesMoved = fileMover.run(vaultRoot);
        int filesFixed = fileChecker.run(mdFiles, FrontmatterRewriter::hasInvalidDate);

        BankruptcyService.BankruptcyResult bankruptcyResult =
            bankruptcy.run(mdFiles, settingsRepo.getBankruptcyLimit());

        SpreadService.SpreadResult spreadResult =
            spread.run(mdFiles, settingsRepo.getMaxDailyReviews());

        fileRepo.triggerDeltaSync();

        // Detect externally-edited files (via Obsidian) by comparing SHA-256 hashes.
        // Any file whose hash changed gets its images re-queued.
        int externallyChanged = 0;
        for (Path mdPath : mdFiles) {
            String absPath = mdPath.toAbsolutePath().toString();
            try {
                String content = Files.readString(mdPath);
                String newHash = ImageScanService.sha256(content);
                String storedHash = noteIndex.getContentHash(absPath);
                if (!newHash.equals(storedHash)) {
                    imageScanService.registerImages(absPath, content);
                    externallyChanged++;
                }
            } catch (IOException e) {
                log.warn("[ChronoService] hash check skip {}: {}", absPath, e.getMessage());
            }
        }
        if (externallyChanged > 0) {
            log.info("[ChronoService] {} externally-edited note(s) detected — images re-queued", externallyChanged);
        }

        String today = LocalDate.now().toString();
        settingsRepo.set("chronoLastRunDate", today);
        log.info("[ChronoService] Complete for {}. moved={} fixed={} overdue={} shifted={}",
            today, filesMoved, filesFixed, bankruptcyResult.overdueCount(), spreadResult.moved());

        return new ChronoResult(today, filesMoved, filesFixed, bankruptcyResult, spreadResult);
    }

    public String getLastRunDate() {
        return settingsRepo.getChronoLastRunDate();
    }
}
