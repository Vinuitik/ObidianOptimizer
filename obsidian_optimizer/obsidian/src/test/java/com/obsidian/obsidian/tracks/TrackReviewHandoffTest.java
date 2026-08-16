package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.cards.FsrsService;
import com.obsidian.obsidian.cards.FsrsStateWriter;
import com.obsidian.obsidian.cards.NoteReviewRepository.ReviewRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackReviewHandoffTest {

    @Mock FsrsStateWriter stateWriter;
    TrackReviewHandoff handoff;

    @BeforeEach
    void setUp() {
        FsrsService fsrs = new FsrsService();
        org.springframework.test.util.ReflectionTestUtils.setField(fsrs, "desiredRetention", 0.9);
        handoff = new TrackReviewHandoff(stateWriter, fsrs);
    }

    @Test
    void legacyNote_migratedThenRescheduledToToday() {
        ReviewRow migrated = new ReviewRow("/vault/n.md", 2.3, 2.1, 3,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), null, null);
        when(stateWriter.normalizeLegacy("/vault/n.md")).thenReturn(migrated);

        handoff.seedDueToday("/vault/n.md");

        verify(stateWriter).reschedule("/vault/n.md", LocalDate.now());
        verify(stateWriter, never()).writeState(any(), any(), any(), any(), anyInt());
    }

    @Test
    void alreadyInFsrsPool_justRescheduledToToday() {
        when(stateWriter.normalizeLegacy("/vault/n.md")).thenReturn(null);
        ReviewRow existing = new ReviewRow("/vault/n.md", 2.3, 2.1, 3,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), null, null);
        when(stateWriter.read("/vault/n.md")).thenReturn(existing);

        handoff.seedDueToday("/vault/n.md");

        verify(stateWriter).reschedule("/vault/n.md", LocalDate.now());
        verify(stateWriter, never()).writeState(any(), any(), any(), any(), anyInt());
    }

    @Test
    void brandNewNote_seededWithFreshInitialStateDueToday() {
        when(stateWriter.normalizeLegacy("/vault/n.md")).thenReturn(null);
        when(stateWriter.read("/vault/n.md")).thenReturn(null);

        handoff.seedDueToday("/vault/n.md");

        verify(stateWriter, never()).reschedule(any(), any());
        verify(stateWriter).writeState(eq("/vault/n.md"), any(), any(), eq(LocalDate.now()), eq(1));
    }
}
