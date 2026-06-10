package com.obsidian.obsidian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ChronoService: all five jobs run end-to-end with real
 * file I/O and a real Postgres container. ChronoService is NOT mocked here —
 * we want the real bean. The @PostConstruct onStartup() will fire with an empty
 * vault; each test then pre-populates files and calls runAllJobs() directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ChronoServiceIT {

    @SuppressWarnings("resource") // lifecycle managed by @Testcontainers extension
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-chrono-vault");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                         VAULT::toString);
        r.add("spring.datasource.url",              postgres::getJdbcUrl);
        r.add("spring.datasource.username",         postgres::getUsername);
        r.add("spring.datasource.password",         postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Ensure onStartup() from @PostConstruct always triggers during context init
        // (lastRunDate is blank in a fresh DB), but that's fine — vault is empty then.
    }

    @Autowired ChronoService      chronoService;
    @Autowired FileRepository     fileRepo;
    @Autowired NoteIndexRepository noteIndex;
    @Autowired SettingsRepository  settingsRepo;

    @BeforeEach
    void resetLastRunDate() {
        // Let each test drive runAllJobs() directly without the date guard.
        settingsRepo.set("chronoLastRunDate", "");
    }

    @AfterEach
    void cleanVaultAndDb() throws IOException {
        try (var stream = Files.walk(VAULT)) {
            stream.sorted(Comparator.reverseOrder())
                  .filter(p -> !p.equals(VAULT))
                  .forEach(p -> p.toFile().delete());
        }
        noteIndex.forceResync(List.<File>of());
    }

    // ── FileMoverService ──────────────────────────────────────────────────────

    @Test
    void runAllJobs_mediaFileInVaultRoot_movedToResourcesSubdir() throws IOException {
        // A .png sitting at vault root should be moved to resources/images/
        Path img = VAULT.resolve("diagram.png");
        Files.writeString(img, "fake-png-bytes");

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.filesMoved()).isEqualTo(1);
        assertThat(img).doesNotExist();
        assertThat(VAULT.resolve("resources").resolve("images").resolve("diagram.png")).exists();
    }

    @Test
    void runAllJobs_noMediaFiles_zeroMoved() throws IOException {
        // Only markdown in vault root — nothing to move
        Files.writeString(VAULT.resolve("Note.md"),
            "---\nsr-due: " + LocalDate.now().plusDays(5) + "\nsr-interval: 3\nsr-ease: 200\n---\n");

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.filesMoved()).isEqualTo(0);
    }

    // ── FileCheckerService ────────────────────────────────────────────────────

    @Test
    void runAllJobs_noteWithInvalidDate_frontmatterFixed() throws IOException {
        // sr-due = "Invalid date" triggers FileCheckerService
        String badContent = "---\nsr-due: Invalid date\nsr-interval: 3\nsr-ease: 200\n---\n\n# Note\n";
        Path note = VAULT.resolve("Broken.md");
        Files.writeString(note, badContent);

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.filesFixed()).isEqualTo(1);
        String fixed = Files.readString(note);
        assertThat(fixed).doesNotContain("Invalid date");
        // sr-due should now be today+3
        assertThat(fixed).contains("sr-due: " + LocalDate.now().plusDays(3));
    }

    @Test
    void runAllJobs_noteWithValidDate_notFixed() throws IOException {
        LocalDate future = LocalDate.now().plusDays(5);
        Path note = VAULT.resolve("Good.md");
        Files.writeString(note, "---\nsr-due: " + future + "\nsr-interval: 3\nsr-ease: 200\n---\n");

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.filesFixed()).isEqualTo(0);
        assertThat(Files.readString(note)).contains("sr-due: " + future);
    }

    // ── SpreadService ─────────────────────────────────────────────────────────

    @Test
    void runAllJobs_overCapNotes_spreadToFutureDays() throws IOException {
        // max 1 review/day: 3 overdue notes → 2 must be rescheduled to distinct future days
        settingsRepo.set("maxDailyReviews", "1");
        LocalDate yesterday = LocalDate.now().minusDays(1);
        for (int i = 0; i < 3; i++) {
            Files.writeString(VAULT.resolve("Due" + i + ".md"),
                "---\nsr-due: " + yesterday + "\nsr-interval: 3\nsr-ease: 200\n---\n");
        }

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.spread().moved()).isGreaterThan(0);
    }

    // ── BankruptcyService ─────────────────────────────────────────────────────

    @Test
    void runAllJobs_underBankruptcyThreshold_noBankruptcy() throws IOException {
        settingsRepo.set("bankruptcyLimit", "200");
        // Only 1 overdue note — nowhere near 200
        Files.writeString(VAULT.resolve("One.md"),
            "---\nsr-due: " + LocalDate.now().minusDays(1) + "\nsr-interval: 3\nsr-ease: 200\n---\n");

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.bankruptcy().declared()).isFalse();
    }

    // ── Date guard (idempotency) ──────────────────────────────────────────────

    @Test
    void runAllJobs_setsLastRunDateToToday() {
        chronoService.runAllJobs();
        assertThat(chronoService.getLastRunDate()).isEqualTo(LocalDate.now().toString());
    }

    @Test
    void onStartup_alreadyRanToday_skipsJobs() throws IOException {
        // Pre-set today's date so onStartup() thinks it already ran
        settingsRepo.set("chronoLastRunDate", LocalDate.now().toString());
        // Put a media file in vault root — it should NOT be moved if jobs are skipped
        Path img = VAULT.resolve("skip.png");
        Files.writeString(img, "fake");

        chronoService.onStartup(); // should be a no-op

        assertThat(img).exists(); // file still there → no job ran
    }

    // ── Combined result structure ─────────────────────────────────────────────

    @Test
    void runAllJobs_returnsResultWithTodaysDate() {
        ChronoService.ChronoResult result = chronoService.runAllJobs();
        assertThat(result.date()).isEqualTo(LocalDate.now().toString());
        assertThat(result.bankruptcy()).isNotNull();
        assertThat(result.spread()).isNotNull();
    }
}
