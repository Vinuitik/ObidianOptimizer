package com.obsidian.obsidian.inbox;

import com.obsidian.obsidian.capture.CaptureIngestWorker;
import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.chrono.ChronoService;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inbox triage lifecycle against a real Postgres + temp vault — the seams the
 * unit layer can't see: list() merging the two item shapes, file() graduating a
 * standalone note into the FSRS review queue, acknowledge() clearing an in-place
 * capture without moving the note, discard() trashing the capture's orphaned
 * local media only when NO sibling note survived.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InboxLifecycleIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-inbox-it-vault");
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

    @MockBean ChronoService chronoService;
    // Keep the capture drainer's @Scheduled ticks away from the rows this test authors.
    @MockBean CaptureIngestWorker captureIngestWorker;

    @Autowired InboxController     inbox;
    @Autowired FileRepository      fileRepo;
    @Autowired NoteIndexRepository noteIndex;
    @Autowired CaptureRepository   captureRepo;
    @Autowired JdbcTemplate        jdbc;

    @AfterEach
    void cleanAll() throws IOException {
        try (var stream = Files.walk(VAULT)) {
            stream.sorted(Comparator.reverseOrder())
                  .filter(p -> !p.equals(VAULT))
                  .forEach(p -> p.toFile().delete());
        }
        noteIndex.forceResync(List.<File>of());
        jdbc.execute("TRUNCATE capture");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path write(String rel, String body) throws IOException {
        Path f = VAULT.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
        return f;
    }

    private void reindex() {
        noteIndex.syncWithDisk(fileRepo.listMdPaths().stream()
            .map(Path::toFile).collect(Collectors.toList()));
    }

    private static String standaloneBody(String captureId, String due) {
        return """
            ---
            sr-due: %s
            sr-interval: 3
            sr-ease: 230
            ingest-inbox: true
            ingest-source: https://example.com/lecture
            ingest-suggested-folder: Topic
            capture-id: %s
            capture-seq: 1
            ---
            # Proposed note

            Body synthesized by ingest.
            """.formatted(due, captureId);
    }

    private List<String> duePaths() {
        return fileRepo.getReviewNotesPaged(0, 50).notes();
    }

    private static final String YESTERDAY = LocalDate.now().minusDays(1).toString();

    // ── standalone: list → file → review queue ───────────────────────────────

    @Test
    void standalone_listShowsItem_reviewQueueExcludesInboxUntilFiled() throws IOException {
        captureRepo.create("cap-stand-1", "url", "https://example.com/lecture",
                           "_inbox/_sources/cap-stand-1.md", "Lecture");
        write("_inbox/_sources/cap-stand-1.md", "snapshot");
        Path note = write("_inbox/Proposed.md", standaloneBody("cap-stand-1", YESTERDAY));
        reindex();

        List<InboxController.InboxItem> items = inbox.listItems();
        assertThat(items).hasSize(1);
        InboxController.InboxItem item = items.get(0);
        assertThat(item.inPlace()).isFalse();
        assertThat(item.path()).isEqualTo(note.toAbsolutePath().toString());
        assertThat(item.source()).isEqualTo("https://example.com/lecture");
        assertThat(item.suggestedFolder()).isEqualTo(VAULT.resolve("Topic").toString());
        assertThat(item.captureId()).isEqualTo("cap-stand-1");

        // staged notes are indexed but MUST NOT be in the FSRS review queue
        assertThat(noteIndex.getAllPaths()).contains(note.toString());
        assertThat(duePaths()).noneMatch(p -> p.contains("_inbox"));
    }

    @Test
    void standalone_file_movesNote_stripsInboxKeys_filesCapture_trashesSnapshot() throws IOException {
        captureRepo.create("cap-stand-2", "url", "https://example.com/lecture2",
                           "_inbox/_sources/cap-stand-2.md", "Lecture2");
        write("_inbox/_sources/cap-stand-2.md", "snapshot");
        Path note = write("_inbox/FileMe.md", standaloneBody("cap-stand-2", YESTERDAY));
        reindex();

        String target = VAULT.resolve("Topic").toString();
        inbox.fileNote(note.toString(), target, null);

        // moved out of _inbox into the real folder
        Path filed = VAULT.resolve("Topic/FileMe.md");
        assertThat(filed).exists();
        assertThat(note).doesNotExist();

        // inbox-only frontmatter stripped; the durable capture link kept
        String content = Files.readString(filed);
        assertThat(content).doesNotContain("ingest-inbox").doesNotContain("ingest-source")
                           .doesNotContain("ingest-suggested-folder");
        assertThat(content).contains("capture-id: cap-stand-2");

        // capture fully triaged → filed + source snapshot soft-deleted to _trash
        assertThat(captureRepo.get("cap-stand-2").status()).isEqualTo("filed");
        assertThat(VAULT.resolve("_inbox/_sources/cap-stand-2.md")).doesNotExist();
        try (var trash = Files.list(VAULT.resolve("_trash"))) {
            assertThat(trash.anyMatch(p -> p.getFileName().toString().startsWith("cap-stand-2")))
                .isTrue();
        }

        // ...and only now does the note enter the review queue
        assertThat(duePaths()).contains(filed.toString());
        assertThat(inbox.listItems()).isEmpty();
    }

    @Test
    void standalone_file_secondNotePending_captureStaysProcessing() throws IOException {
        captureRepo.create("cap-multi", "url", "https://example.com/series",
                           "_inbox/_sources/cap-multi.md", "Series");
        write("_inbox/_sources/cap-multi.md", "snapshot");
        Path first  = write("_inbox/Part1.md", standaloneBody("cap-multi", YESTERDAY));
        write("_inbox/Part2.md", standaloneBody("cap-multi", YESTERDAY));
        reindex();

        inbox.fileNote(first.toString(), VAULT.resolve("Topic").toString(), null);

        // a sibling is still staged → the capture is NOT filed yet, snapshot survives
        assertThat(captureRepo.get("cap-multi").status()).isEqualTo("processing");
        assertThat(VAULT.resolve("_inbox/_sources/cap-multi.md")).exists();
    }

    // ── in-place: list → acknowledge ─────────────────────────────────────────

    @Test
    void inPlace_listedViaCaptureId_staysInReview_acknowledgeFilesAndTrashesSnapshot() throws IOException {
        String body = """
            ---
            sr-due: %s
            sr-interval: 3
            sr-ease: 230
            capture-id: cap-inplace
            capture-seq: 1
            ---
            # Existing note

            ![[lecture.mp4]]
            <!-- ingest:lecture.mp4 sha=abc -->synthesized block<!-- /ingest:lecture.mp4 -->
            """.formatted(YESTERDAY);
        Path note = write("Topic/Existing.md", body);
        captureRepo.create("cap-inplace", "note", "Topic/Existing.md",
                           "_inbox/_sources/cap-inplace.md", "Existing");
        write("_inbox/_sources/cap-inplace.md", "pre-rewrite snapshot");
        reindex();

        // listed as an in-place item via capture_id — no directory scan involved
        List<InboxController.InboxItem> items = inbox.listItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).inPlace()).isTrue();
        assertThat(items.get(0).path()).isEqualTo(note.toString());
        assertThat(items.get(0).captureId()).isEqualTo("cap-inplace");

        // unlike standalone staging, an in-place note NEVER leaves the review queue
        assertThat(duePaths()).contains(note.toString());

        inbox.acknowledgeCapture("cap-inplace");

        assertThat(captureRepo.get("cap-inplace").status()).isEqualTo("filed");
        assertThat(VAULT.resolve("_inbox/_sources/cap-inplace.md")).doesNotExist();
        assertThat(note).exists();                       // nothing moved
        assertThat(inbox.listItems()).isEmpty();         // filed captures drop out
        assertThat(duePaths()).contains(note.toString()); // still due for review
    }

    // ── discard + local-media retention ──────────────────────────────────────

    @Test
    void discard_lastNoteOfCapture_trashesNoteAndOrphanedLocalMedia() throws IOException {
        captureRepo.create("cap-media", "url", "https://youtube.com/watch?v=x",
                           null, "Video");
        Path media = write("resources/media/talk.mp4", "fake-video-bytes");
        String body = standaloneBody("cap-media", YESTERDAY)
            + "\n## Source\nlocal: resources/media/talk.mp4\n";
        Path note = write("_inbox/OnlyNote.md", body);
        reindex();

        inbox.discardNote(note.toString());

        assertThat(note).doesNotExist();
        assertThat(noteIndex.getAllPaths()).doesNotContain(note.toString());
        // last surviving note of the capture → its downloaded media is orphaned → trashed
        assertThat(media).doesNotExist();
        try (var trash = Files.list(VAULT.resolve("_trash"))) {
            assertThat(trash.anyMatch(p -> p.getFileName().toString().startsWith("talk")))
                .isTrue();
        }
    }

    @Test
    void discard_siblingNoteSurvives_localMediaKept() throws IOException {
        captureRepo.create("cap-media2", "url", "https://youtube.com/watch?v=y",
                           null, "Video2");
        Path media = write("resources/media/keep.mp4", "fake-video-bytes");
        String body = standaloneBody("cap-media2", YESTERDAY)
            + "\n## Source\nlocal: resources/media/keep.mp4\n";
        Path keepNote    = write("_inbox/Kept.md", body);
        Path discardNote = write("_inbox/Dropped.md", body);
        reindex();

        // file one sibling first — its index row keeps findNotesByCapture non-empty
        inbox.fileNote(keepNote.toString(), VAULT.resolve("Topic").toString(), null);
        inbox.discardNote(discardNote.toString());

        assertThat(media).exists();   // a fragment of the source was kept → media survives
    }

    // ── list filtering ───────────────────────────────────────────────────────

    @Test
    void list_ignoresFiledCaptures_andNonNoteSources() throws IOException {
        // filed in-place capture → not listed
        write("Topic/Done.md", "---\ncapture-id: cap-done\n---\nbody");
        captureRepo.create("cap-done", "note", "Topic/Done.md", null, "Done");
        captureRepo.updateStatus("cap-done", "filed");
        // queued url capture (durable queue row, no notes yet) → not an inbox item
        captureRepo.enqueue("cap-queued", "url", "https://example.com/z", null, "Z");
        reindex();

        assertThat(inbox.listItems()).isEmpty();
    }
}
