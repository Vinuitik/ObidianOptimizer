package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.cards.CardRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
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
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the readiness gate: a note carrying an un-ingested resource embed
 * (ingest_pending = true) is excluded from BOTH the embedding and the card
 * worklists, and ResourceScanService.scan() sets AND clears the flag correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ReadinessGateIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-gate-vault");
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

    @Autowired NoteIndexRepository noteIndex;
    @Autowired CardRepository cardRepo;
    @Autowired ResourceScanService resourceScan;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // CASCADE: cards is referenced by FKs (assignments/attempts) — truncate the
        // whole graph so each test starts empty.
        jdbc.execute("TRUNCATE notes, cards, card_gen_attempts CASCADE");
    }

    /** Insert a note row directly with the fields the worklists key on. */
    private void insertNote(String path, String hash, LocalDate srDue, boolean pending) {
        jdbc.update(
            "INSERT INTO notes(path, title, modified_at, content_hash, sr_due, ingest_pending) VALUES (?,?,?,?,?,?)",
            path, path.substring(path.lastIndexOf('/') + 1), System.currentTimeMillis(),
            hash, srDue == null ? null : Date.valueOf(srDue), pending);
    }

    private boolean ingestPending(String path) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT ingest_pending FROM notes WHERE path = ?", Boolean.class, path));
    }

    @Test
    void embeddingWorklist_excludesIngestPendingNotes() {
        insertNote("/vault/ready.md",   "h1", null, false);
        insertNote("/vault/pending.md", "h2", null, true);

        Map<String, String> work = noteIndex.findNotesNeedingEmbedding(10);

        assertThat(work).containsKey("/vault/ready.md");
        assertThat(work).doesNotContainKey("/vault/pending.md");
    }

    @Test
    void cardWorklist_excludesIngestPendingNotes() {
        insertNote("/vault/ready.md",   "h3", LocalDate.now(), false);
        insertNote("/vault/pending.md", "h4", LocalDate.now(), true);

        List<String> paths = cardRepo.findNotesNeedingCards(10).stream()
            .map(m -> (String) m.get("path")).toList();

        assertThat(paths).contains("/vault/ready.md");
        assertThat(paths).doesNotContain("/vault/pending.md");
    }

    @Test
    void scan_setsFlagForUnIngestedEmbed_thenClearsItOnceMarkerLands() {
        insertNote("/vault/lecture.md", "h5", null, false);

        // Un-ingested video embed → flag set → excluded from the worklist.
        resourceScan.scan("/vault/lecture.md", "intro\n![[clip.mp4]]\nnotes");
        assertThat(ingestPending("/vault/lecture.md")).isTrue();
        assertThat(noteIndex.findNotesNeedingEmbedding(10)).doesNotContainKey("/vault/lecture.md");

        // Ingest finished: marker injected → re-scan clears the flag → now eligible.
        resourceScan.scan("/vault/lecture.md",
            "intro\n![[clip.mp4]]\n<!-- ingest:clip.mp4 2026-06-14 -->\ntranscript text");
        assertThat(ingestPending("/vault/lecture.md")).isFalse();
        assertThat(noteIndex.findNotesNeedingEmbedding(10)).containsKey("/vault/lecture.md");
    }
}
