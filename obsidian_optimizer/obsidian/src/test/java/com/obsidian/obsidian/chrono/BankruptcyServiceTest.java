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
 * Two-pass bankruptcy: chronic neglect (always, per-note) then mass lapse (threshold).
 * All run() calls take (mdFiles, bankruptcyLimit, chronicNeglectDays).
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

    // ── Declaration gate (structural) ────────────────────────────────────────

    @Test
    void notDeclaredWhenOverdueCountBelowLimit() throws IOException {
        Path f = writeNote("n.md", pastDate(1), 5, 250);
        var result = service.run(List.of(f), 2, 365);  // 365d neglect threshold: nothing is chronic
        assertThat(result.declared()).isFalse();
        assertThat(result.overdueCount()).isEqualTo(1);
        assertThat(result.rescheduled()).isEqualTo(0);
        assertThat(FrontmatterRewriter.readFsrs(f)).isNull();   // untouched below threshold
    }

    @Test
    void declaredWhenOverdueCountEqualsLimit() throws IOException {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) files.add(writeNote("n" + i + ".md", pastDate(1), 5, 250));
        var result = service.run(files, 3, 365);
        assertThat(result.declared()).isTrue();
        assertThat(result.rescheduled()).isEqualTo(3);
    }

    @Test
    void futureNotesNotCountedAsOverdue() throws IOException {
        Path future = writeNote("future.md", LocalDate.now().plusDays(5), 5, 250);
        Path overdue = writeNote("past.md", pastDate(1), 5, 250);
        var result = service.run(List.of(future, overdue), 2, 365);
        assertThat(result.declared()).isFalse();
        assertThat(result.overdueCount()).isEqualTo(1);
    }

    @Test
    void emptyFileListReturnsFalseResult() {
        var result = service.run(List.of(), 1, 7);
        assertThat(result.declared()).isFalse();
        assertThat(result.overdueCount()).isEqualTo(0);
    }

    // ── Chronic neglect pass (always runs, per-note) ─────────────────────────

    @Test
    void chronicNeglect_lapsesLongOverdueNote_withoutBankruptcyThreshold() throws IOException {
        Path chronic = writeNote("chronic.md", pastDate(30), 5, 250);  // 30 days overdue
        Path recent  = writeNote("recent.md",  pastDate(3),  5, 250);  // 3 days overdue

        // bankruptcyLimit = 200 (never triggered), chronicNeglectDays = 7
        var result = service.run(List.of(chronic, recent), 200, 7);

        assertThat(result.declared()).isFalse();
        assertThat(result.chronicNeglected()).isEqualTo(1);
        assertThat(FrontmatterRewriter.readFsrs(chronic)).isNotNull();  // lapsed + FSRS written
        assertThat(FrontmatterRewriter.readFsrs(recent)).isNull();      // untouched — only 3 days
    }

    @Test
    void chronicNote_isLapsed_evenWhenTotalOverdueBelowBankruptcyLimit() throws IOException {
        Path f = writeNote("old.md", pastDate(14), 20, 250);
        var result = service.run(List.of(f), 200, 7);

        assertThat(result.chronicNeglected()).isEqualTo(1);
        var fsrs = FrontmatterRewriter.readFsrs(f);
        assertThat(fsrs).isNotNull();
        assertThat(fsrs.stability()).isLessThan(20.0);  // forget collapsed memory
        assertThat(FrontmatterRewriter.read(f).due()).isAfter(LocalDate.now());
    }

    // ── The mass lapse itself ────────────────────────────────────────────────

    @Test
    void legacyNote_isSeededIntoFsrs_andStabilityCollapses() throws IOException {
        Path f = writeNote("med.md", pastDate(1), 20, 250);
        service.run(List.of(f), 1, 365);

        var fsrs = FrontmatterRewriter.readFsrs(f);
        assertThat(fsrs).isNotNull();
        assertThat(fsrs.stability()).isLessThan(20.0);
        assertThat(fsrs.interval()).isLessThanOrEqualTo(20);
        assertThat(fsrs.difficulty()).isGreaterThan(6.0);
        assertThat(FrontmatterRewriter.read(f).ease()).isEqualTo(250);  // legacy ease untouched
    }

    @Test
    void existingFsrsState_isLapsedFromItsRealStability_notReseeded() throws IOException {
        Path f = writeNote("known.md", pastDate(1), 5, 250);
        when(reviewRepo.find(f.toAbsolutePath().toString())).thenReturn(new ReviewRow(
            f.toAbsolutePath().toString(), 50.0, 3.0, 4,
            Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)),
            Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), "dMid:sLong", 1.2));

        service.run(List.of(f), 1, 365);

        var fsrs = FrontmatterRewriter.readFsrs(f);
        assertThat(fsrs.stability()).isLessThan(50.0);
        assertThat(fsrs.arm()).isEqualTo(1.2);
        assertThat(fsrs.bucket()).isEqualTo("dMid:sLong");
    }

    @Test
    void rescheduledNoteHasFutureDueDate() throws IOException {
        Path f = writeNote("past.md", pastDate(5), 5, 250);
        service.run(List.of(f), 1, 365);
        assertThat(FrontmatterRewriter.read(f).due()).isAfter(LocalDate.now());
    }

    @Test
    void writesGoThroughTheDb_too() throws IOException {
        Path f = writeNote("dbcheck.md", pastDate(1), 10, 250);
        service.run(List.of(f), 1, 365);
        verify(reviewRepo).upsert(eq(f.toAbsolutePath().toString()), anyDouble(), anyDouble(),
            any(), any(), any(), any());
    }
}
