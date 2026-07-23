package com.obsidian.obsidian.pwa;

import com.obsidian.obsidian.capture.CaptureIngestWorker;
import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.chrono.ChronoService;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.sync.DriveService;
import com.obsidian.obsidian.sync.DriveService.MailboxFile;
import com.obsidian.obsidian.sync.VaultEncryptionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phone→server mailbox consume against real Postgres + real AES-GCM encryption —
 * the correctness hinge per pwa/FLOWS.md: a mailbox file is deleted ONLY when every
 * event committed, and the consumed_events ledger makes any reprocessing a no-op.
 * Drive is an in-memory fake (Mockito); the encrypted JSON envelope here IS the
 * phone's write contract, so a format drift fails this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MailboxConsumeIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-mailbox-it-vault");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          VAULT::toString);
        r.add("sync.passphrase",                     () -> "mailbox-it-passphrase");
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockBean ChronoService chronoService;
    @MockBean CaptureIngestWorker captureIngestWorker;
    @MockBean DriveService drive;
    @MockBean OfflineExportService offlineExport;

    @Autowired MailboxConsumeService  consume;
    @Autowired VaultEncryptionService encryption;
    @Autowired ConsumedEventRepository ledger;
    @Autowired FileRepository         fileRepo;
    @Autowired NoteIndexRepository    noteIndex;
    @Autowired CaptureRepository      captureRepo;
    @Autowired JdbcTemplate           jdbc;

    private static final String YESTERDAY = LocalDate.now().minusDays(1).toString();

    @BeforeEach
    void driveUp() {
        when(drive.isConfigured()).thenReturn(true);
    }

    @AfterEach
    void cleanAll() throws IOException {
        try (var stream = Files.walk(VAULT)) {
            stream.sorted(Comparator.reverseOrder())
                  .filter(p -> !p.equals(VAULT))
                  .forEach(p -> p.toFile().delete());
        }
        noteIndex.forceResync(List.<File>of());
        jdbc.execute("TRUNCATE consumed_events");
        jdbc.execute("TRUNCATE capture");
        jdbc.execute("DELETE FROM note_reviews");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path writeNote(String rel, String body) throws IOException {
        Path f = VAULT.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
        return f;
    }

    private void reindex() {
        noteIndex.syncWithDisk(fileRepo.listMdPaths().stream()
            .map(Path::toFile).collect(Collectors.toList()));
    }

    private static String reviewableBody() {
        return """
            ---
            sr-due: %s
            sr-interval: 3
            sr-ease: 230
            ---
            # Note under review
            """.formatted(YESTERDAY);
    }

    /** Encrypt a phone-shaped mailbox envelope and serve it as the only Drive file. */
    private void stageMailbox(String json) throws Exception {
        byte[] enc = encryption.encrypt(json.getBytes());
        when(drive.listMailbox()).thenReturn(
            new ArrayList<>(List.of(new MailboxFile("f1", "1700000000-phone.enc"))));
        when(drive.downloadFile("f1")).thenReturn(enc);
    }

    private String gradeEvent(String eventId, String notePath, String band) {
        return """
            {"kind":"grade","eventId":"%s","notePath":"%s","band":"%s"}
            """.formatted(eventId, notePath.replace("\\", "\\\\"), band).trim();
    }

    private static String envelope(String... events) {
        return "{\"deviceId\":\"phone-test\",\"events\":[" + String.join(",", events) + "]}";
    }

    // ── the happy path: grade applied, file deleted, ledger written ──────────

    @Test
    void gradeEvents_applied_fileDeleted_ledgerWritten_reExportTriggered() throws Exception {
        Path note = writeNote("Topic/Graded.md", reviewableBody());
        reindex();
        stageMailbox(envelope(gradeEvent("e-1", note.toString(), "GOOD")));

        int applied = consume.consumeAll();

        assertThat(applied).isEqualTo(1);
        // FSRS state written through the single write path: DB row + frontmatter mirror
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM note_reviews WHERE note_path = ?", Integer.class, note.toString());
        assertThat(rows).isEqualTo(1);
        assertThat(Files.readString(note)).contains("fsrs-s:");
        // hinge: every event committed → the mailbox file is deleted
        verify(drive).deleteFile("f1");
        assertThat(ledger.alreadyConsumed("e-1")).isTrue();
        // the phone's next pull must reflect this grade
        verify(offlineExport).exportReviewBundle(200);
    }

    // ── idempotency: reprocessing a file is a no-op ──────────────────────────

    @Test
    void replayedFile_isNoOp_andStillGetsDeleted() throws Exception {
        Path note = writeNote("Topic/Replayed.md", reviewableBody());
        reindex();
        stageMailbox(envelope(gradeEvent("e-dup", note.toString(), "GOOD")));

        assertThat(consume.consumeAll()).isEqualTo(1);
        String stateAfterFirst = Files.readString(note);

        // same file surfaces again (delete raced/failed last pass) → ledger short-circuits
        stageMailbox(envelope(gradeEvent("e-dup", note.toString(), "GOOD")));
        int replayApplied = consume.consumeAll();

        assertThat(replayApplied).isZero();
        assertThat(Files.readString(note)).isEqualTo(stateAfterFirst); // no double-grade
        verify(drive, times(2)).deleteFile("f1"); // fully-skipped file still cleans up
    }

    // ── partial failure: file survives, committed events don't reapply ───────

    @Test
    void partialFailure_keepsFile_committedEventNotReappliedOnRetry() throws Exception {
        Path good = writeNote("Topic/Good.md", reviewableBody());
        reindex();
        String bad = """
            {"kind":"grade","eventId":"e-bad","notePath":"%s","band":"NOT_A_BAND"}
            """.formatted(good.toString().replace("\\", "\\\\")).trim();
        stageMailbox(envelope(gradeEvent("e-good", good.toString(), "EASY"), bad));

        int applied = consume.consumeAll();

        assertThat(applied).isEqualTo(1);                    // good event committed
        verify(drive, never()).deleteFile(anyString());      // bad event → file retained
        assertThat(ledger.alreadyConsumed("e-good")).isTrue();
        assertThat(ledger.alreadyConsumed("e-bad")).isFalse();

        // retry pass: the good event is skipped (no double-grade), the bad one fails again
        String stateAfterFirst = Files.readString(good);
        stageMailbox(envelope(gradeEvent("e-good", good.toString(), "EASY"), bad));
        assertThat(consume.consumeAll()).isZero();
        assertThat(Files.readString(good)).isEqualTo(stateAfterFirst);
        verify(drive, never()).deleteFile(anyString());      // still looping — the known P6 gap
    }

    // ── forward compatibility: unknown kinds preserve the file ───────────────

    @Test
    void unknownKind_leavesFileForNewerServer() throws Exception {
        stageMailbox(envelope(
            "{\"kind\":\"capture\",\"eventId\":\"e-cap\",\"url\":\"https://example.com\"}"));

        int applied = consume.consumeAll();

        assertThat(applied).isZero();
        verify(drive, never()).deleteFile(anyString());
        assertThat(ledger.alreadyConsumed("e-cap")).isFalse();
    }

    @Test
    void malformedEnvelope_droppedWithoutApplying() throws Exception {
        stageMailbox("{\"deviceId\":\"phone\",\"nope\":true}");

        assertThat(consume.consumeAll()).isZero();
        verify(drive).deleteFile("f1"); // junk is not retried forever
    }

    // ── P5 kinds: inbox triage rides the same pipe ───────────────────────────

    @Test
    void fileAndAcknowledgeEvents_replayInboxTriage() throws Exception {
        // standalone note staged in _inbox + its capture
        captureRepo.create("cap-mb", "url", "https://example.com/mb",
                           "_inbox/_sources/cap-mb.md", "MB");
        writeNote("_inbox/_sources/cap-mb.md", "snapshot");
        Path staged = writeNote("_inbox/FromPhone.md", """
            ---
            sr-due: %s
            sr-interval: 3
            sr-ease: 230
            ingest-inbox: true
            ingest-source: https://example.com/mb
            capture-id: cap-mb
            capture-seq: 1
            ---
            # Phone-triaged note
            """.formatted(YESTERDAY));
        reindex();

        String target = VAULT.resolve("Topic").toString();
        String fileEvent = """
            {"kind":"file","eventId":"e-file","path":"%s","targetFolder":"%s","content":null}
            """.formatted(staged.toString().replace("\\", "\\\\"),
                          target.replace("\\", "\\\\")).trim();
        stageMailbox(envelope(fileEvent));

        int applied = consume.consumeAll();

        assertThat(applied).isEqualTo(1);
        assertThat(VAULT.resolve("Topic/FromPhone.md")).exists();
        assertThat(staged).doesNotExist();
        assertThat(captureRepo.get("cap-mb").status()).isEqualTo("filed");
        verify(drive).deleteFile("f1");
    }
}
