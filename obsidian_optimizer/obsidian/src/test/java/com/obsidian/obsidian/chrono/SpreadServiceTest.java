package com.obsidian.obsidian.chrono;

import com.obsidian.obsidian.cards.FsrsStateWriter;
import com.obsidian.obsidian.cards.NoteReviewRepository;
import com.obsidian.obsidian.notes.FileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpreadServiceTest {

    @TempDir Path tmp;
    SpreadService service;

    @BeforeEach
    void setUp() {
        // Legacy notes move via direct frontmatter rewrite (no DB); FSRS notes
        // go through the writer, whose repo/file deps we mock.
        service = new SpreadService(new FsrsStateWriter(
            mock(NoteReviewRepository.class), mock(FileRepository.class)));
    }

    private Path writeNote(String name, LocalDate due, int interval, int ease) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, "---\nsr-due: " + due + "\nsr-interval: " + interval
            + "\nsr-ease: " + ease + "\n---\n");
        return f;
    }

    private Path writeFsrsNote(String name, LocalDate due, double difficulty) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, "---\nsr-due: " + due + "\nsr-interval: 10\nfsrs-s: 10.000000\n"
            + "fsrs-d: " + String.format(java.util.Locale.ROOT, "%.6f", difficulty)
            + "\nfsrs-last: " + due.minusDays(10) + "\nfsrs-arm: 1.000000\nfsrs-bucket: dMid:sMid\n---\n");
        return f;
    }

    @Test
    void withinCap_noteNotMoved() throws IOException {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Path f = writeNote("n1.md", tomorrow, 5, 250);
        var result = service.run(List.of(f), 5);
        assertThat(result.moved()).isEqualTo(0);
        assertThat(FrontmatterRewriter.read(f).due()).isEqualTo(tomorrow);
    }

    @Test
    void overCap_oneNoteMovedToNextDay() throws IOException {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Path n1 = writeNote("n1.md", tomorrow, 5, 200);
        Path n2 = writeNote("n2.md", tomorrow, 5, 300);
        var result = service.run(List.of(n1, n2), 1);
        assertThat(result.moved()).isEqualTo(1);
        LocalDate d1 = FrontmatterRewriter.read(n1).due();
        LocalDate d2 = FrontmatterRewriter.read(n2).due();
        assertThat(List.of(d1, d2)).containsExactlyInAnyOrder(tomorrow, tomorrow.plusDays(1));
    }

    @Test
    void legacy_lowestEaseNoteStaysOnOverloadedDay() throws IOException {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Path n1 = writeNote("n1.md", tomorrow, 5, 200);   // lower ease = harder
        Path n2 = writeNote("n2.md", tomorrow, 5, 300);
        service.run(List.of(n1, n2), 1);
        assertThat(FrontmatterRewriter.read(n1).due()).isEqualTo(tomorrow);   // hardest stays
        assertThat(FrontmatterRewriter.read(n2).due()).isAfter(tomorrow);
    }

    @Test
    void fsrs_highestDifficultyStaysOnOverloadedDay() throws IOException {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Path hard = writeFsrsNote("hard.md", tomorrow, 8.0);
        Path easy = writeFsrsNote("easy.md", tomorrow, 3.0);
        service.run(List.of(hard, easy), 1);
        assertThat(FrontmatterRewriter.read(hard).due()).isEqualTo(tomorrow);  // hardest stays
        assertThat(FrontmatterRewriter.read(easy).due()).isAfter(tomorrow);
    }

    @Test
    void overdueNotes_cascadeForwardThroughToday() throws IOException {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Path n1 = writeNote("n1.md", yesterday, 5, 200);
        Path n2 = writeNote("n2.md", yesterday, 5, 300);
        var result = service.run(List.of(n1, n2), 1);
        assertThat(result.moved()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void emptyList_returnsZero() {
        var result = service.run(List.of(), 5);
        assertThat(result.total()).isEqualTo(0);
        assertThat(result.moved()).isEqualTo(0);
    }

    @Test
    void fileWithoutFrontmatter_isIgnored() throws IOException {
        Path f = tmp.resolve("plain.md");
        Files.writeString(f, "# Just a heading\n\nNo frontmatter.");
        var result = service.run(List.of(f), 5);
        assertThat(result.total()).isEqualTo(0);
    }

    @Test
    void totalCountReflectsAllNotesWithFrontmatter() throws IOException {
        LocalDate d = LocalDate.now().plusDays(1);
        Path n1 = writeNote("n1.md", d, 5, 200);
        Path n2 = writeNote("n2.md", d, 5, 300);
        var result = service.run(List.of(n1, n2), 10);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.moved()).isEqualTo(0);
    }
}
