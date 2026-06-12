package com.obsidian.obsidian.cards;

import com.obsidian.obsidian.cards.NoteReviewRepository.ReviewRow;
import com.obsidian.obsidian.cards.ReviewService.Band;
import com.obsidian.obsidian.cards.ReviewService.GradeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceTest {

    @Mock BanditService bandit;
    @Mock NoteReviewRepository reviewRepo;

    ReviewService service;
    final Instant now = Instant.parse("2026-06-12T10:00:00Z");

    @BeforeEach
    void setUp() {
        FsrsService fsrs = new FsrsService();
        ReflectionTestUtils.setField(fsrs, "desiredRetention", 0.9);
        when(bandit.bucket(anyDouble(), anyDouble())).thenReturn("dEasy:sShort");
        when(bandit.chooseArm(anyString())).thenReturn(1.0);
        service = new ReviewService(fsrs, bandit, reviewRepo);
    }

    @Test
    void firstGrade_createsInitialFsrsState_noRewardYet() {
        when(reviewRepo.find("/vault/n.md")).thenReturn(null);

        GradeResult result = service.grade("/vault/n.md", Band.GOOD, now);

        assertThat(result.stability()).isCloseTo(2.3065, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(result.baseIntervalDays()).isEqualTo(2);
        verify(bandit, never()).reward(anyString(), anyDouble(), anyBoolean());
        verify(reviewRepo).upsert(eq("/vault/n.md"), anyDouble(), anyDouble(),
            any(), any(), eq("dEasy:sShort"), eq(1.0));
    }

    @Test
    void secondGrade_paysDelayedRewardToPreviousArm() {
        ReviewRow prev = new ReviewRow("/vault/n.md", 2.3065, 2.118104, 1,
            Timestamp.from(now.minus(3, ChronoUnit.DAYS)),
            Timestamp.from(now), "dEasy:sShort", 1.2);
        when(reviewRepo.find("/vault/n.md")).thenReturn(prev);

        service.grade("/vault/n.md", Band.GOOD, now);

        verify(bandit).reward("dEasy:sShort", 1.2, true);   // recalled
    }

    @Test
    void hardBand_countsAsForgotten_forTheBanditReward() {
        ReviewRow prev = new ReviewRow("/vault/n.md", 2.3065, 2.118104, 1,
            Timestamp.from(now.minus(3, ChronoUnit.DAYS)),
            Timestamp.from(now), "dEasy:sShort", 1.5);
        when(reviewRepo.find("/vault/n.md")).thenReturn(prev);

        service.grade("/vault/n.md", Band.HARD, now);

        verify(bandit).reward("dEasy:sShort", 1.5, false);  // not recalled
    }

    @Test
    void banditArmScalesTheScheduledDate_butNotTheStoredState() {
        when(reviewRepo.find("/vault/n.md")).thenReturn(null);
        when(bandit.chooseArm(anyString())).thenReturn(1.5);

        GradeResult result = service.grade("/vault/n.md", Band.EASY, now);

        // base FSRS interval for first Easy is 8 days; the arm stretches the
        // due date to 12 days but stability stays pure FSRS (8.2956)
        assertThat(result.baseIntervalDays()).isEqualTo(8);
        assertThat(result.banditArm()).isEqualTo(1.5);
        assertThat(result.due().toInstant()).isEqualTo(now.plus(12, ChronoUnit.DAYS));
        assertThat(result.stability()).isCloseTo(8.2956, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void veryEasyMapsToFsrsEasy_butIsItsOwnBandLabel() {
        when(reviewRepo.find("/vault/n.md")).thenReturn(null);
        GradeResult veryEasy = service.grade("/vault/n.md", Band.VERY_EASY, now);
        GradeResult easy     = service.grade("/vault/n.md", Band.EASY, now);
        assertThat(veryEasy.stability()).isEqualTo(easy.stability());
        assertThat(veryEasy.band()).isEqualTo("VERY_EASY");
    }

    @Test
    void scoreBandMapping_matchesGradeBands() {
        assertThat(Band.fromScore(0.10)).isEqualTo(Band.HARD);
        assertThat(Band.fromScore(0.39)).isEqualTo(Band.HARD);
        assertThat(Band.fromScore(0.40)).isEqualTo(Band.GOOD);
        assertThat(Band.fromScore(0.69)).isEqualTo(Band.GOOD);
        assertThat(Band.fromScore(0.70)).isEqualTo(Band.EASY);
        assertThat(Band.fromScore(0.89)).isEqualTo(Band.EASY);
        assertThat(Band.fromScore(0.90)).isEqualTo(Band.VERY_EASY);
        assertThat(Band.fromScore(1.00)).isEqualTo(Band.VERY_EASY);
    }
}
