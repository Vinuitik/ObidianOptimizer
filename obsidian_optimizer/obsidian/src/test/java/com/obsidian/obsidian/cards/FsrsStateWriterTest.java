package com.obsidian.obsidian.cards;

import com.obsidian.obsidian.cards.FsrsService.FsrsState;
import com.obsidian.obsidian.cards.NoteReviewRepository.ReviewRow;
import com.obsidian.obsidian.notes.FileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The DB↔frontmatter mirror and its volume-reset recovery: a wiped DB must be
 * rehydratable from the note frontmatter alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FsrsStateWriterTest {

    @Mock NoteReviewRepository reviewRepo;
    @Mock FileRepository fileRepo;

    private FsrsStateWriter writer() { return new FsrsStateWriter(reviewRepo, fileRepo); }

    private Path note(Path dir, String body) throws Exception {
        Path p = dir.resolve("note.md");
        Files.writeString(p, body);
        return p;
    }

    @Test
    void write_mirrorsToDbAndFrontmatter_preservingEase(@TempDir Path dir) throws Exception {
        Path p = note(dir, "---\nsr-due: 2026-06-01\nsr-interval: 3\nsr-ease: 250\ntitle: x\n---\nbody\n");

        writer().write(p.toString(), new FsrsState(13.8269, 2.1112),
            Instant.parse("2026-06-12T10:00:00Z"),
            Timestamp.from(Instant.parse("2026-06-26T10:00:00Z")), 14, "dEasy:sMid", 1.2);

        verify(reviewRepo).upsert(eq(p.toString()), eq(13.8269), eq(2.1112),
            any(), any(), eq("dEasy:sMid"), eq(1.2));
        verify(fileRepo).reindexAfterExternalWrite(p.toString());

        String written = Files.readString(p);
        assertThat(written).contains("fsrs-s: 13.826900");
        assertThat(written).contains("fsrs-d: 2.111200");
        assertThat(written).contains("fsrs-arm: 1.200000");
        assertThat(written).contains("fsrs-bucket: dEasy:sMid");
        assertThat(written).contains("sr-ease: 250");   // legacy field untouched
        assertThat(written).contains("title: x");        // unrelated field untouched
    }

    @Test
    void read_returnsDbRow_whenPresent_withoutTouchingFrontmatter(@TempDir Path dir) throws Exception {
        ReviewRow dbRow = new ReviewRow("/vault/n.md", 5.0, 4.0, 2,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), "dMid:sShort", 0.85);
        when(reviewRepo.find("/vault/n.md")).thenReturn(dbRow);

        assertThat(writer().read("/vault/n.md")).isSameAs(dbRow);
        verify(reviewRepo, never()).upsert(any(), anyDouble(), anyDouble(), any(), any(), any(), anyDouble());
    }

    @Test
    void read_hydratesFromFrontmatter_whenDbMissing_andBackfillsDb(@TempDir Path dir) throws Exception {
        Path p = note(dir, "---\nsr-due: 2026-06-26\nsr-interval: 14\nfsrs-s: 13.826900\n"
            + "fsrs-d: 2.111200\nfsrs-last: 2026-06-12\nfsrs-arm: 1.200000\nfsrs-bucket: dEasy:sMid\n---\nbody\n");
        when(reviewRepo.find(p.toString())).thenReturn(null);

        ReviewRow row = writer().read(p.toString());

        assertThat(row).isNotNull();
        assertThat(row.stability()).isEqualTo(13.8269);
        assertThat(row.difficulty()).isEqualTo(2.1112);
        assertThat(row.pendingBucket()).isEqualTo("dEasy:sMid");
        assertThat(row.pendingArm()).isEqualTo(1.2);
        // backfilled into the DB so the next query is fast again
        verify(reviewRepo).upsert(eq(p.toString()), eq(13.8269), eq(2.1112),
            any(), any(), eq("dEasy:sMid"), eq(1.2));
    }

    @Test
    void read_returnsNull_whenNoStateAnywhere(@TempDir Path dir) throws Exception {
        Path p = note(dir, "---\nsr-due: 2026-06-26\nsr-interval: 14\n---\nbody\n"); // legacy only, no fsrs-*
        when(reviewRepo.find(p.toString())).thenReturn(null);

        assertThat(writer().read(p.toString())).isNull();
    }
}
