package com.obsidian.obsidian.inbox;

import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Split endpoint: manually spawning an additional note for the same source region, ordered
 * by capture-seq-minor so nothing else is renumbered. The repos are mocked to read/write the
 * temp vault so nextSplitMinor's _inbox filesystem scan sees files created by earlier splits.
 */
@ExtendWith(MockitoExtension.class)
class InboxControllerSplitTest {

    @Mock FileRepository repository;
    @Mock SettingsRepository settingsRepo;
    @Mock NoteIndexRepository noteIndex;
    @Mock CaptureRepository captureRepo;

    private InboxController controller;
    private Path vault;
    private Path inbox;

    @BeforeEach
    void setUp() throws IOException {
        vault = Files.createTempDirectory("vault");
        inbox = Files.createDirectories(vault.resolve("_inbox"));
        controller = new InboxController(repository, settingsRepo, noteIndex, captureRepo);

        lenient().when(settingsRepo.getVaultPath()).thenReturn(vault.toString());
        // getText reads the file back off disk.
        lenient().when(repository.getText(anyString())).thenAnswer(inv ->
            Files.readString(Path.of(inv.getArgument(0, String.class))));
        // createNote writes a placeholder (like the real one) and returns the abs path so a
        // later split's directory scan finds it; updateNote overwrites with the real content.
        AtomicInteger n = new AtomicInteger();
        lenient().when(repository.createNote(anyString(), anyString())).thenAnswer(inv -> {
            Path f = inbox.resolve(inv.getArgument(1, String.class) + ".md");
            if (Files.exists(f)) throw new IOException("Note already exists: " + f);
            Files.writeString(f, "placeholder " + n.incrementAndGet());
            return f.toString();
        });
        lenient().doAnswer(inv -> {
            Files.writeString(Path.of(inv.getArgument(0, String.class)),
                              inv.getArgument(1, String.class));
            return null;
        }).when(repository).updateNote(anyString(), anyString());
    }

    private String write(String name, String content) throws IOException {
        Path f = inbox.resolve(name + ".md");
        Files.writeString(f, content);
        return f.toString();
    }

    private static String note(String captureId, int seq, Integer minor, String body) {
        String fm = "---\ningest-inbox: true\ningest-source: pdf/book.pdf\n"
                  + "capture-id: " + captureId + "\ncapture-seq: " + seq + "\n"
                  + (minor != null ? "capture-seq-minor: " + minor + "\n" : "")
                  + "---\n\n" + body + "\n";
        return fm;
    }

    @Test
    void split_createsSiblingAtMinorOne_sameSourceRegion() throws Exception {
        String orig = write("Chapter 21-23", note("cap1", 10, null, "pages 21-23 body"));

        ResponseEntity<?> res = controller.split(new InboxController.SplitRequest(orig));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body.get("captureSeqMinor")).isEqualTo(1);

        String created = Files.readString(Path.of((String) body.get("path")));
        // Same capture (groups together), same seq (slots right after), source region preserved.
        assertThat(created).contains("capture-id: cap1");
        assertThat(created).contains("capture-seq: 10");
        assertThat(created).contains("capture-seq-minor: 1");
        assertThat(created).contains("ingest-source: pdf/book.pdf");
        assertThat(created).contains("pages 21-23 body");
    }

    @Test
    void split_twice_incrementsMinorWithoutRenumbering() throws Exception {
        String orig = write("Chapter 21-23", note("cap1", 10, null, "body"));

        controller.split(new InboxController.SplitRequest(orig));   // → minor 1
        ResponseEntity<?> second = controller.split(new InboxController.SplitRequest(orig)); // → minor 2

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) second.getBody();
        assertThat(body.get("captureSeqMinor")).isEqualTo(2);
        // The original is untouched — no renumbering of anything else.
        assertThat(Files.readString(Path.of(orig))).doesNotContain("capture-seq-minor");
    }

    @Test
    void split_rejectsNonInboxPath() {
        ResponseEntity<?> res = controller.split(
            new InboxController.SplitRequest("/vault/Real/note.md"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void upsertFrontmatter_insertsWhenAbsent_replacesWhenPresent() {
        String base = "---\ncapture-id: c\ncapture-seq: 3\n---\n\nbody\n";
        String inserted = InboxController.upsertFrontmatter(base, "capture-seq-minor", "1");
        assertThat(inserted).contains("capture-seq-minor: 1");
        assertThat(inserted).contains("body");

        String replaced = InboxController.upsertFrontmatter(inserted, "capture-seq-minor", "2");
        assertThat(replaced).contains("capture-seq-minor: 2");
        assertThat(replaced).doesNotContain("capture-seq-minor: 1");
        // exactly one occurrence — replaced in place, not appended
        assertThat(replaced.split("capture-seq-minor:", -1).length - 1).isEqualTo(1);
    }
}
