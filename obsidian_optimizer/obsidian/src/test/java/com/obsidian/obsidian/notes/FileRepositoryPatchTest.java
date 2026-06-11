package com.obsidian.obsidian.notes;

import com.obsidian.obsidian.ml.ImageScanService;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.SyncQueueRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileRepositoryPatchTest {

    @TempDir Path tmp;

    @Mock NoteLinkRepository noteLinkRepo;
    @Mock SettingsRepository  settingsRepo;
    @Mock NoteIndexRepository noteIndex;
    @Mock ImageScanService    imageScanService;
    @Mock SyncQueueRepository syncQueueRepo;

    FileRepository repo;

    @BeforeEach
    void setUp() {
        doNothing().when(noteIndex).upsert(anyString(), anyString(), any(), anyLong());
        doNothing().when(noteLinkRepo).updateLinks(anyString(), any());
        // requireInsideVault needs a vault root — init() against the temp dir
        org.mockito.Mockito.when(settingsRepo.getVaultPath()).thenReturn(tmp.toString());
        org.mockito.Mockito.when(settingsRepo.getStartupSyncMode()).thenReturn("blocking");
        repo = new FileRepository(noteLinkRepo, settingsRepo, noteIndex, imageScanService, syncQueueRepo);
        repo.init();
    }

    private Path writeNote(String name, String content) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    @Test
    void singleLineInsertion() throws IOException {
        Path f = writeNote("ins.md", "line0\nline2\nline3");
        repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(1, 0, List.of("line1"))
        ));
        assertThat(Files.readString(f)).isEqualTo("line0\nline1\nline2\nline3");
    }

    @Test
    void singleLineDeletion() throws IOException {
        Path f = writeNote("del.md", "line0\nBAD\nline2");
        repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(1, 1, List.of())
        ));
        assertThat(Files.readString(f)).isEqualTo("line0\nline2");
    }

    @Test
    void singleLineReplacement() throws IOException {
        Path f = writeNote("repl.md", "a\nb\nc");
        repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(1, 1, List.of("B"))
        ));
        assertThat(Files.readString(f)).isEqualTo("a\nB\nc");
    }

    @Test
    void insertionAtStartOfFile() throws IOException {
        Path f = writeNote("start.md", "existing");
        repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(0, 0, List.of("new first line"))
        ));
        assertThat(Files.readString(f)).isEqualTo("new first line\nexisting");
    }

    @Test
    void insertionAtEndOfFile() throws IOException {
        Path f = writeNote("end.md", "first\nsecond");
        repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(2, 0, List.of("third"))
        ));
        assertThat(Files.readString(f)).isEqualTo("first\nsecond\nthird");
    }

    @Test
    void multipleHunksAppliedBackToFront() throws IOException {
        Path f = writeNote("multi.md", "alpha\nbeta\ngamma\ndelta");
        repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(0, 1, List.of("ALPHA")),
            new FileRepository.PatchHunk(2, 1, List.of("GAMMA"))
        ));
        assertThat(Files.readString(f)).isEqualTo("ALPHA\nbeta\nGAMMA\ndelta");
    }

    @Test
    void multiLineInsertionHunk() throws IOException {
        Path f = writeNote("multi-ins.md", "first\nlast");
        repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(1, 0, List.of("second", "third"))
        ));
        assertThat(Files.readString(f)).isEqualTo("first\nsecond\nthird\nlast");
    }

    @Test
    void preservesCrlfSeparator() throws IOException {
        Path f = writeNote("crlf.md", "a\r\nb\r\nc");
        repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(1, 1, List.of("B"))
        ));
        String result = Files.readString(f);
        assertThat(result).contains("\r\n");
        assertThat(result).contains("B");
        assertThat(result).doesNotContain("\nb");
    }

    @Test
    void emptyHunkListIsNoOp() throws IOException {
        Path f = writeNote("noop.md", "unchanged content");
        repo.patchNote(f.toString(), List.of());
        assertThat(Files.readString(f)).isEqualTo("unchanged content");
    }

    @Test
    void nullHunkListIsNoOp() throws IOException {
        Path f = writeNote("null.md", "unchanged");
        repo.patchNote(f.toString(), null);
        assertThat(Files.readString(f)).isEqualTo("unchanged");
    }

    @Test
    void outOfRangeHunkThrowsIOException() throws IOException {
        Path f = writeNote("oor.md", "only one line");
        assertThatThrownBy(() -> repo.patchNote(f.toString(), List.of(
            new FileRepository.PatchHunk(99, 1, List.of("x"))
        ))).isInstanceOf(IOException.class)
           .hasMessageContaining("out of range");
    }

    @Test
    void nonExistentFileThrowsIOException() {
        assertThatThrownBy(() -> repo.patchNote(
            tmp.resolve("ghost.md").toString(),
            List.of(new FileRepository.PatchHunk(0, 1, List.of("x")))
        )).isInstanceOf(IOException.class);
    }
}
