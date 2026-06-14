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
        jdbc.execute("TRUNCATE pending_image_jobs");
    }

    /** Insert a note with body_hash = content_hash (the common case). */
    private void insertNote(String path, String hash, LocalDate srDue, boolean pending) {
        insertNote(path, hash, hash, srDue, pending);
    }

    /** Insert a note with distinct content_hash and body_hash. */
    private void insertNote(String path, String contentHash, String bodyHash, LocalDate srDue, boolean pending) {
        jdbc.update(
            "INSERT INTO notes(path, title, modified_at, content_hash, body_hash, sr_due, ingest_pending) VALUES (?,?,?,?,?,?,?)",
            path, path.substring(path.lastIndexOf('/') + 1), System.currentTimeMillis(),
            contentHash, bodyHash, srDue == null ? null : Date.valueOf(srDue), pending);
    }

    /** Simulate a generated, ACTIVE card whose source_hash is the note's body_hash. */
    private void insertCard(String path, String sourceHash) {
        jdbc.update(
            "INSERT INTO cards(note_path, type, payload, difficulty, card_hash, source_hash, status) " +
            "VALUES (?,?,?::jsonb,?,?,?,?)",
            path, "mcq", "{}", 1, "card-" + sourceHash, sourceHash, "ACTIVE");
    }

    private void insertImageJob(String notePath, String imagePath, String status) {
        jdbc.update(
            "INSERT INTO pending_image_jobs(note_path, image_path, status) VALUES (?,?,?)",
            notePath, imagePath, status);
    }

    private List<String> cardWorklistPaths() {
        return cardRepo.findNotesNeedingCards(10).stream().map(m -> (String) m.get("path")).toList();
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

    @Test
    void cardWorklist_keysOnBodyHash_frontmatterEditDoesNotRetrigger() {
        // A note with body=b1 that already has an ACTIVE card generated from b1.
        insertNote("/vault/note.md", "c1", "b1", LocalDate.now(), false);
        insertCard("/vault/note.md", "b1");
        assertThat(cardWorklistPaths()).doesNotContain("/vault/note.md");   // already covered

        // Frontmatter-only edit (e.g. sr-due rewrite on review): content_hash changes,
        // body_hash unchanged → must NOT re-enter the worklist (this is the credit fix).
        jdbc.update("UPDATE notes SET content_hash = ? WHERE path = ?", "c2", "/vault/note.md");
        assertThat(cardWorklistPaths()).doesNotContain("/vault/note.md");

        // Real body edit: body_hash changes → no ACTIVE card matches → eligible again.
        jdbc.update("UPDATE notes SET body_hash = ? WHERE path = ?", "b2", "/vault/note.md");
        assertThat(cardWorklistPaths()).contains("/vault/note.md");
    }

    @Test
    void cardWorklist_waitsForNoteImagesToFinish() {
        insertNote("/vault/diagram.md", "h", LocalDate.now(), false);

        // An image is still being captioned → cards wait (they now consume image text).
        insertImageJob("/vault/diagram.md", "fig1.png", "PENDING");
        assertThat(cardWorklistPaths()).doesNotContain("/vault/diagram.md");

        // Image finished → descriptions exist → cards eligible.
        jdbc.update("UPDATE pending_image_jobs SET status = 'DONE' WHERE note_path = ? AND image_path = ?",
            "/vault/diagram.md", "fig1.png");
        assertThat(cardWorklistPaths()).contains("/vault/diagram.md");
    }

    @Test
    void cardWorklist_skippedImageDoesNotBlockForever() {
        insertNote("/vault/broken-img.md", "h", LocalDate.now(), false);
        insertImageJob("/vault/broken-img.md", "missing.png", "SKIPPED");
        // SKIPPED (image gone / permanently failed) must not gate cards.
        assertThat(cardWorklistPaths()).contains("/vault/broken-img.md");
    }
}
