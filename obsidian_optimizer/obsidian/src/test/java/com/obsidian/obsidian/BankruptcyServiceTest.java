package com.obsidian.obsidian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BankruptcyServiceTest {

    @TempDir Path tmp;
    BankruptcyService service = new BankruptcyService();

    private Path writeNote(String name, LocalDate due, int interval, int ease) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, "---\nsr-due: " + due + "\nsr-interval: " + interval + "\nsr-ease: " + ease + "\n---\n\nBody.");
        return f;
    }

    private LocalDate pastDate(int daysAgo) {
        return LocalDate.now().minusDays(daysAgo);
    }

    // ── Threshold logic ──────────────────────────────────────────────────────

    @Test
    void notDeclaredWhenOverdueCountBelowLimit() throws IOException {
        Path f = writeNote("n.md", pastDate(1), 5, 250);
        var result = service.run(List.of(f), 2);
        assertThat(result.declared()).isFalse();
        assertThat(result.overdueCount()).isEqualTo(1);
        assertThat(result.rescheduled()).isEqualTo(0);
    }

    @Test
    void declaredWhenOverdueCountEqualsLimit() throws IOException {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) files.add(writeNote("n" + i + ".md", pastDate(1), 5, 250));
        var result = service.run(files, 3);
        assertThat(result.declared()).isTrue();
        assertThat(result.rescheduled()).isEqualTo(3);
    }

    @Test
    void futureNotesNotCountedAsOverdue() throws IOException {
        Path future = writeNote("future.md", LocalDate.now().plusDays(5), 5, 250);
        Path overdue = writeNote("past.md", pastDate(1), 5, 250);
        var result = service.run(List.of(future, overdue), 2);
        assertThat(result.declared()).isFalse();
        assertThat(result.overdueCount()).isEqualTo(1);
    }

    @Test
    void emptyFileListReturnsFalseResult() {
        var result = service.run(List.of(), 1);
        assertThat(result.declared()).isFalse();
        assertThat(result.overdueCount()).isEqualTo(0);
    }

    // ── Tier interval logic ──────────────────────────────────────────────────
    // Triggers bankruptcy (limit = 1) for each tier to verify the new interval.

    @Test
    void tierShort_intervalAtOrBelow7_becomesMinInterval2() throws IOException {
        Path f = writeNote("short.md", pastDate(1), 7, 400);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).interval()).isEqualTo(2);
    }

    @Test
    void tierShort_intervalOf1_becomesMinInterval2() throws IOException {
        Path f = writeNote("tiny.md", pastDate(1), 1, 300);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).interval()).isEqualTo(2);
    }

    @Test
    void tierMedium_interval8to30_isHalved() throws IOException {
        Path f = writeNote("med.md", pastDate(1), 20, 400);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).interval()).isEqualTo(10);
    }

    @Test
    void tierMedium_intervalOf8_isHalvedTo4() throws IOException {
        Path f = writeNote("med8.md", pastDate(1), 8, 400);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).interval()).isEqualTo(4);
    }

    @Test
    void tierLong_interval31to90_becomes21() throws IOException {
        Path f = writeNote("long.md", pastDate(1), 60, 400);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).interval()).isEqualTo(21);
    }

    @Test
    void tierVeryLong_intervalAbove90_becomes45() throws IOException {
        Path f = writeNote("vlong.md", pastDate(1), 100, 400);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).interval()).isEqualTo(45);
    }

    // ── Ease floor logic ─────────────────────────────────────────────────────

    @Test
    void easeHighEnough_isHalvedNormally() throws IOException {
        // 500/2 = 250 > 215 floor → result is 250
        Path f = writeNote("hi.md", pastDate(1), 5, 500);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).ease()).isEqualTo(250);
    }

    @Test
    void easeHalfBelowMinEase_isClamped() throws IOException {
        // 300/2 = 150 < 215 → clamped to 215
        Path f = writeNote("lo.md", pastDate(1), 5, 300);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).ease()).isEqualTo(215);
    }

    @Test
    void easeExactlyMinEase_staysAtMinEase() throws IOException {
        // 215/2 = 107 < 215 → clamped to 215
        Path f = writeNote("exact.md", pastDate(1), 5, 215);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).ease()).isEqualTo(215);
    }

    // ── New due date is in the future ────────────────────────────────────────

    @Test
    void rescheduledNoteHasFutureDueDate() throws IOException {
        Path f = writeNote("past.md", pastDate(5), 5, 250);
        service.run(List.of(f), 1);
        var fields = FrontmatterRewriter.read(f);
        assertThat(fields.due()).isAfter(LocalDate.now());
    }
}
