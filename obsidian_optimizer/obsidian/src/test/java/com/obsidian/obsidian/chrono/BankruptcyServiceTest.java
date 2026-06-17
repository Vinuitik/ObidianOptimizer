package com.obsidian.obsidian.chrono;

import com.obsidian.obsidian.cards.FsrsService;
import com.obsidian.obsidian.cards.FsrsStateWriter;
import com.obsidian.obsidian.cards.NoteReviewRepository;
import com.obsidian.obsidian.cards.NoteReviewRepository.ReviewRow;
import com.obsidian.obsidian.notes.FileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Bankruptcy as the FSRS mass-lapse: overdue notes get {@link FsrsService#forget}
 * (stability collapses, difficulty rises) and are rescheduled by their new
 * interval. Legacy notes are seeded into FSRS first.
 */
class BankruptcyServiceTest {

    @TempDir Path tmp;

    NoteReviewRepository reviewRepo;
    FileRepository fileRepo;
    BankruptcyService service;

    @BeforeEach
    void setUp() {
        reviewRepo = mock(NoteReviewRepository.class);
        fileRepo = mock(FileRepository.class);
        FsrsService fsrs = new FsrsService();
        ReflectionTestUtils.setField(fsrs, "desiredRetention", 0.9);
        service = new BankruptcyService(fsrs, new FsrsStateWriter(reviewRepo, fileRepo));
    }

    private Path writeNote(String name, LocalDate due, int interval, int ease) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, "---\nsr-due: " + due + "\nsr-interval: " + interval
            + "\nsr-ease: " + ease + "\n---\n\nBody.");
        return f;
    }

    private LocalDate pastDate(int daysAgo) { return LocalDate.now().minusDays(daysAgo); }

    // ── Declaration gate (structural, no FSRS math) ──────────────────────────

    @Test
    void notDeclaredWhenOverdueCountBelowLimit() throws IOException {
        Path f = writeNote("n.md", pastDate(1), 5, 250);
        var result = service.run(List.of(f), 2);
        assertThat(result.declared()).isFalse();
        assertThat(result.overdueCount()).isEqualTo(1);
        assertThat(result.rescheduled()).isEqualTo(0);
        assertThat(FrontmatterRewriter.readFsrs(f)).isNull();   // untouched below threshold
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

    // ── The lapse itself ─────────────────────────────────────────────────────

    @Test
    void legacyNote_isSeededIntoFsrs_andStabilityCollapses() throws IOException {
        Path f = writeNote("med.md", pastDate(1), 20, 250);   // seed stability ≈ 20
        service.run(List.of(f), 1);

        var fsrs = FrontmatterRewriter.readFsrs(f);
        assertThat(fsrs).isNotNull();                          // joined the FSRS world
        assertThat(fsrs.stability()).isLessThan(20.0);         // forget collapsed memory
        assertThat(fsrs.interval()).isLessThanOrEqualTo(20);   // shorter next interval
        // seeded difficulty from ease 250 is 6.0; the lapse raises it further
        assertThat(fsrs.difficulty()).isGreaterThan(6.0);
        assertThat(FrontmatterRewriter.read(f).ease()).isEqualTo(250);  // legacy ease untouched
    }

    @Test
    void existingFsrsState_isLapsedFromItsRealStability_notReseeded() throws IOException {
        Path f = writeNote("known.md", pastDate(1), 5, 250);
        // DB already has high-stability FSRS state for this note.
        when(reviewRepo.find(f.toAbsolutePath().toString())).thenReturn(new ReviewRow(
            f.toAbsolutePath().toString(), 50.0, 3.0, 4,
            Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)),
            Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), "dMid:sLong", 1.2));

        service.run(List.of(f), 1);

        var fsrs = FrontmatterRewriter.readFsrs(f);
        assertThat(fsrs.stability()).isLessThan(50.0);   // collapsed from the real 50, not a seed
        // pending bandit decision preserved (no review happened during bankruptcy)
        assertThat(fsrs.arm()).isEqualTo(1.2);
        assertThat(fsrs.bucket()).isEqualTo("dMid:sLong");
    }

    @Test
    void rescheduledNoteHasFutureDueDate() throws IOException {
        Path f = writeNote("past.md", pastDate(5), 5, 250);
        service.run(List.of(f), 1);
        assertThat(FrontmatterRewriter.read(f).due()).isAfter(LocalDate.now());
    }

    @Test
    void writesGoThroughTheDb_too() throws IOException {
        Path f = writeNote("dbcheck.md", pastDate(1), 10, 250);
        service.run(List.of(f), 1);
        // FsrsStateWriter mirrored to Postgres as well as the file.
        verify(reviewRepo).upsert(eq(f.toAbsolutePath().toString()), anyDouble(), anyDouble(),
            any(), any(), any(), any());
    }
}
