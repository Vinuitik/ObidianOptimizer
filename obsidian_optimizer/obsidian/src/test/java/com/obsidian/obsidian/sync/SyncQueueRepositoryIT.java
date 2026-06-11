package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.sync.SyncQueueRepository.SyncEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SyncQueueRepositoryIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-syncq-vault");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          VAULT::toString);
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired SyncQueueRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.execute("TRUNCATE sync_queue");
    }

    // ── Basic transitions ─────────────────────────────────────────────────────

    @Test
    void markPending_insertsPendingRow() {
        repo.markPending("notes/a.md", "hash1");
        SyncEntry entry = repo.findByPath("notes/a.md");
        assertThat(entry).isNotNull();
        assertThat(entry.status()).isEqualTo("PENDING");
        assertThat(entry.contentHash()).isEqualTo("hash1");
        assertThat(entry.retryCount()).isZero();
    }

    @Test
    void markDoneIfHashMatches_transitionsToDone_whenHashUnchanged() {
        repo.markPending("notes/a.md", "hash1");
        boolean done = repo.markDoneIfHashMatches("notes/a.md", "drive-id-1", "hash1");
        assertThat(done).isTrue();
        SyncEntry entry = repo.findByPath("notes/a.md");
        assertThat(entry.status()).isEqualTo("DONE");
        assertThat(entry.driveFileId()).isEqualTo("drive-id-1");
        assertThat(entry.lastSyncedAt()).isNotNull();
    }

    @Test
    void markFailed_incrementsRetryCount_markPendingResetsIt() {
        repo.markPending("notes/a.md", "hash1");
        repo.markFailed("notes/a.md");
        repo.markFailed("notes/a.md");
        assertThat(repo.findByPath("notes/a.md").retryCount()).isEqualTo(2);
        assertThat(repo.findByPath("notes/a.md").status()).isEqualTo("FAILED");

        repo.markPending("notes/a.md", "hash2");
        SyncEntry entry = repo.findByPath("notes/a.md");
        assertThat(entry.retryCount()).isZero();
        assertThat(entry.status()).isEqualTo("PENDING");
    }

    // ── The bug #3 race: edit lands mid-upload ────────────────────────────────

    @Test
    void editDuringUpload_keepsRowPending_soNewContentUploadsNextPass() {
        // 1. note edited → queued
        repo.markPending("notes/race.md", "hash-v1");

        // 2. upload worker reads the PENDING batch (captures hash-v1) …
        SyncEntry snapshot = repo.findByStatus("PENDING").get(0);

        // 3. … meanwhile the user edits the note again
        repo.markPending("notes/race.md", "hash-v2");

        // 4. worker finishes uploading v1 and tries to mark DONE with its stale hash
        boolean done = repo.markDoneIfHashMatches("notes/race.md", "drive-id-1", snapshot.contentHash());

        // The DONE must be refused: v2 still needs uploading
        assertThat(done).isFalse();
        SyncEntry entry = repo.findByPath("notes/race.md");
        assertThat(entry.status()).isEqualTo("PENDING");
        assertThat(entry.contentHash()).isEqualTo("hash-v2");
    }

    @Test
    void noEditDuringUpload_marksDoneNormally() {
        repo.markPending("notes/calm.md", "hash-v1");
        SyncEntry snapshot = repo.findByStatus("PENDING").get(0);
        boolean done = repo.markDoneIfHashMatches("notes/calm.md", "drive-id-9", snapshot.contentHash());
        assertThat(done).isTrue();
        assertThat(repo.findByPath("notes/calm.md").status()).isEqualTo("DONE");
    }

    // ── Download bookkeeping ──────────────────────────────────────────────────

    @Test
    void markSynced_upsertsRowForFileCreatedOnAnotherDevice() {
        // no local queue row exists for this path
        repo.markSynced("notes/from-other-device.md", "remote-hash", "drive-id-7");
        SyncEntry entry = repo.findByPath("notes/from-other-device.md");
        assertThat(entry).isNotNull();
        assertThat(entry.status()).isEqualTo("DONE");
        assertThat(entry.contentHash()).isEqualTo("remote-hash");
        assertThat(entry.driveFileId()).isEqualTo("drive-id-7");
    }

    @Test
    void markSynced_overwritesExistingRowWithDriveState() {
        repo.markPending("notes/b.md", "old-local-hash");
        repo.markSynced("notes/b.md", "drive-hash", "drive-id-2");
        SyncEntry entry = repo.findByPath("notes/b.md");
        assertThat(entry.status()).isEqualTo("DONE");
        assertThat(entry.contentHash()).isEqualTo("drive-hash");
    }
}
