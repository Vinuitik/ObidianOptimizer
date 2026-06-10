package com.obsidian.obsidian;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    public ChronoService(FileMoverService fileMover, FileCheckerService fileChecker,
                         BankruptcyService bankruptcy, SpreadService spread,
                         SettingsRepository settingsRepo, FileRepository fileRepo) {
        this.fileMover    = fileMover;
        this.fileChecker  = fileChecker;
        this.bankruptcy   = bankruptcy;
        this.spread       = spread;
        this.settingsRepo = settingsRepo;
        this.fileRepo     = fileRepo;
    }

    public record ChronoResult(
        String date,
        int filesMoved,
        int filesFixed,
        BankruptcyService.BankruptcyResult bankruptcy,
        SpreadService.SpreadResult spread
    ) {}

    // Runs after FileRepository has completed its own @PostConstruct startup sync
    // (guaranteed because FileRepository is a dependency of this bean).
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
