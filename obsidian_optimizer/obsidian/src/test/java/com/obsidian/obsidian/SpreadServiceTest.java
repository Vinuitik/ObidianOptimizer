package com.obsidian.obsidian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadServiceTest {

    @TempDir Path tmp;
    SpreadService service = new SpreadService();

    private Path writeNote(String name, LocalDate due, int interval, int ease) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, "---\nsr-due: " + due + "\nsr-interval: " + interval + "\nsr-ease: " + ease + "\n---\n");
        return f;
    }

    // ── Within cap ───────────────────────────────────────────────────────────

    @Test
    void withinCap_noteNotMoved() throws IOException {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Path f = writeNote("n1.md", tomorrow, 5, 250);
        var result = service.run(List.of(f), 5);
        assertThat(result.moved()).isEqualTo(0);
        assertThat(FrontmatterRewriter.read(f).due()).isEqualTo(tomorrow);
    }

    @Test
    void exactlyAtCap_nothingMoved() throws IOException {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Path n1 = writeNote("n1.md", tomorrow, 5, 200);
        Path n2 = writeNote("n2.md", tomorrow, 5, 300);
        var result = service.run(List.of(n1, n2), 2);
        assertThat(result.moved()).isEqualTo(0);
    }

    // ── Over cap ─────────────────────────────────────────────────────────────

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

    // ── Lowest-ease stays ────────────────────────────────────────────────────

    @Test
    void lowestEaseNoteStaysOnOverloadedDay() throws IOException {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        // n1 has lower ease (harder) → stays; n2 (easier) → moved
        Path n1 = writeNote("n1.md", tomorrow, 5, 200);
        Path n2 = writeNote("n2.md", tomorrow, 5, 300);
        service.run(List.of(n1, n2), 1);
        assertThat(FrontmatterRewriter.read(n1).due()).isEqualTo(tomorrow);
        assertThat(FrontmatterRewriter.read(n2).due()).isAfter(tomorrow);
    }

    // ── Cascade ──────────────────────────────────────────────────────────────

    @Test
    void cascade_overflowRipplesThroughConsecutiveDays() throws IOException {
        LocalDate d1 = LocalDate.now().plusDays(1);
        LocalDate d2 = LocalDate.now().plusDays(2);
        // 2 notes on d1, 1 on d2; cap=1 → d1 overflows to d2 → d2 overflows to d3
        Path a = writeNote("a.md", d1, 5, 200);
        Path b = writeNote("b.md", d1, 5, 300);
        Path c = writeNote("c.md", d2, 5, 200);
        service.run(List.of(a, b, c), 1);
        // Each note must be on a unique day (no day has > 1)
        LocalDate da = FrontmatterRewriter.read(a).due();
        LocalDate db = FrontmatterRewriter.read(b).due();
        LocalDate dc = FrontmatterRewriter.read(c).due();
        assertThat(da).isNotEqualTo(db);
        assertThat(db).isNotEqualTo(dc);
        assertThat(da).isNotEqualTo(dc);
    }

    // ── Overdue notes ────────────────────────────────────────────────────────

    @Test
    void overdueNotes_cascadeForwardThroughToday() throws IOException {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Path n1 = writeNote("n1.md", yesterday, 5, 200);
        Path n2 = writeNote("n2.md", yesterday, 5, 300);
        // cap=1: one overdue stays on its delta, one cascades forward
        var result = service.run(List.of(n1, n2), 1);
        assertThat(result.moved()).isGreaterThanOrEqualTo(1);
    }

    // ── Empty/no-frontmatter ──────────────────────────────────────────────────

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

    // ── Total count ──────────────────────────────────────────────────────────

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
