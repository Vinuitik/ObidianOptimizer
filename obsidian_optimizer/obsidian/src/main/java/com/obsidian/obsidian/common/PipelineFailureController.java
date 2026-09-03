package com.obsidian.obsidian.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read/resolve surface for the shared {@code pipeline_failures} ledger (see
 * PipelineFailureRepository, QUEUE_UNIFICATION_PLAN.md) — the "Pipeline Failures" page's
 * backend. Every backend pipeline dead-letter already writes here; the one new write path
 * is {@link #report}, for a failure that dead-ends client-side BEFORE any durable row
 * exists to retry (e.g. the browser extension's initial capture POST itself was rejected,
 * or a client-side extraction had no fallback left) — anything that gets past that point
 * is already covered by the owning pipeline's own dead-letter write.
 */
@RestController
public class PipelineFailureController {

    private final PipelineFailureRepository repo;

    public PipelineFailureController(PipelineFailureRepository repo) {
        this.repo = repo;
    }

    @GetMapping("pipeline-failures")
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "true") boolean onlyOpen,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "200") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PipelineFailureRepository.Failure f : repo.list(onlyOpen, source, stage, limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("occurredAt", f.occurredAt());
            m.put("source", f.source());
            m.put("stage", f.stage());
            m.put("inputPayload", f.inputPayload());
            m.put("errorType", f.errorType());
            m.put("errorMessage", f.errorMessage());
            m.put("bundleRef", f.bundleRef());
            m.put("resolvedAt", f.resolvedAt());
            out.add(m);
        }
        return out;
    }

    /** No generic retry here (payload shapes differ per source/stage — replay is
     *  source-specific, e.g. capture's own retry-ladder or its /capture/{id}/retry). This
     *  is a debugging ledger: mark it looked-at/fixed, nothing more. */
    @PostMapping("pipeline-failures/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable long id) {
        if (!repo.resolve(id)) {
            return ResponseEntity.status(409).body(Map.of("error", "not open"));
        }
        return ResponseEntity.ok(Map.of("status", "resolved"));
    }

    @PostMapping("pipeline-failures")
    public ResponseEntity<Map<String, Object>> report(@RequestBody ReportRequest body) {
        if (body == null || body.source() == null || body.source().isBlank()
                || body.stage() == null || body.stage().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "source and stage required"));
        }
        Map<String, Object> payload = body.input() != null ? body.input() : Map.of();
        repo.record(body.source(), body.stage(), payload, null, body.error(), null);
        return ResponseEntity.ok(Map.of("status", "recorded"));
    }

    public record ReportRequest(String source, String stage, Map<String, Object> input, String error) {}
}
