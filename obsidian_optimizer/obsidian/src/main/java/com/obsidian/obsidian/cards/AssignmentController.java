package com.obsidian.obsidian.cards;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class AssignmentController {

    private final AssignmentService assignmentService;

    AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /** Build an assignment worth {points} from {scope} (note path or folder). */
    @PostMapping("assignments")
    public ResponseEntity<?> build(@RequestBody BuildRequest req) {
        try {
            return ResponseEntity.ok(assignmentService.build(req.scope(), req.points()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** Verify one answer and record the attempt. */
    @PostMapping("attempts")
    public ResponseEntity<?> attempt(@RequestBody AttemptRequest req) {
        try {
            return ResponseEntity.ok(assignmentService.submitAttempt(
                req.assignmentId(), req.cardId(), req.answer()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Finish: per-note scores → bands → FSRS + bandit scheduling. */
    @PostMapping("assignments/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(assignmentService.complete(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    record BuildRequest(String scope, int points) {}
    record AttemptRequest(UUID assignmentId, UUID cardId, String answer) {}
}
