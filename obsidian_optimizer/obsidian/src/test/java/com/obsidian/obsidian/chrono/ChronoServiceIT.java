package com.obsidian.obsidian.chrono;

import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ChronoServiceIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

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
    }

    @Autowired ChronoService      chronoService;
    @Autowired FileRepository     fileRepo;
    @Autowired NoteIndexRepository noteIndex;
    @Autowired SettingsRepository  settingsRepo;

    @BeforeEach
    void resetLastRunDate() {
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

    @Test
    void runAllJobs_mediaFileInVaultRoot_movedToResourcesSubdir() throws IOException {
        Path img = VAULT.resolve("diagram.png");
        Files.writeString(img, "fake-png-bytes");

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.filesMoved()).isEqualTo(1);
        assertThat(img).doesNotExist();
        assertThat(VAULT.resolve("resources").resolve("images").resolve("diagram.png")).exists();
    }

    @Test
    void runAllJobs_noMediaFiles_zeroMoved() throws IOException {
        Files.writeString(VAULT.resolve("Note.md"),
            "---\nsr-due: " + LocalDate.now().plusDays(5) + "\nsr-interval: 3\nsr-ease: 200\n---\n");

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.filesMoved()).isEqualTo(0);
    }

    @Test
    void runAllJobs_overCapNotes_spreadToFutureDays() throws IOException {
        settingsRepo.set("maxDailyReviews", "1");
        LocalDate yesterday = LocalDate.now().minusDays(1);
        for (int i = 0; i < 3; i++) {
            Files.writeString(VAULT.resolve("Due" + i + ".md"),
                "---\nsr-due: " + yesterday + "\nsr-interval: 3\nsr-ease: 200\n---\n");
        }

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.spread().moved()).isGreaterThan(0);
    }

    @Test
    void runAllJobs_underBankruptcyThreshold_noBankruptcy() throws IOException {
        settingsRepo.set("bankruptcyLimit", "200");
        Files.writeString(VAULT.resolve("One.md"),
            "---\nsr-due: " + LocalDate.now().minusDays(1) + "\nsr-interval: 3\nsr-ease: 200\n---\n");

        ChronoService.ChronoResult result = chronoService.runAllJobs();

        assertThat(result.bankruptcy().declared()).isFalse();
    }

    @Test
    void runAllJobs_setsLastRunDateToToday() {
        chronoService.runAllJobs();
        assertThat(chronoService.getLastRunDate()).isEqualTo(LocalDate.now().toString());
    }

    @Test
    void onStartup_alreadyRanToday_skipsJobs() throws IOException {
        settingsRepo.set("chronoLastRunDate", LocalDate.now().toString());
        Path img = VAULT.resolve("skip.png");
        Files.writeString(img, "fake");

        chronoService.onStartup();

        assertThat(img).exists();
    }

    @Test
    void runAllJobs_returnsResultWithTodaysDate() {
        ChronoService.ChronoResult result = chronoService.runAllJobs();
        assertThat(result.date()).isEqualTo(LocalDate.now().toString());
        assertThat(result.bankruptcy()).isNotNull();
        assertThat(result.spread()).isNotNull();
    }
}
