package com.obsidian.obsidian.cards;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ReviewController {

    private final ReviewService reviewService;
    private final NoteReviewRepository reviewRepo;

    ReviewController(ReviewService reviewService, NoteReviewRepository reviewRepo) {
        this.reviewService = reviewService;
        this.reviewRepo = reviewRepo;
    }

    /** Notes due for review now, most overdue first. */
    @GetMapping("reviews/due")
    public List<Map<String, Object>> due(@RequestParam(defaultValue = "50") int limit) {
        return reviewRepo.findDue(limit);
    }

    /**
     * Grade one note. Slideshow mode posts the user's button press directly;
     * flashcards mode posts the band computed from the test score.
     */
    @PostMapping("reviews/grade")
    public ResponseEntity<?> grade(@RequestBody GradeRequest req) {
        ReviewService.Band band;
        try {
            band = ReviewService.Band.valueOf(req.band().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest()
                .body("band must be one of HARD, GOOD, EASY, VERY_EASY");
        }
        return ResponseEntity.ok(reviewService.grade(req.notePath(), band));
    }

    record GradeRequest(String notePath, String band) {}
}
