package com.obsidian.obsidian.chrono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChronoController {

    private static final Logger log = LoggerFactory.getLogger(ChronoController.class);

    private final ChronoService chronoService;

    ChronoController(ChronoService chronoService) {
        this.chronoService = chronoService;
    }

    @GetMapping("chrono/status")
    public ChronoStatusResponse getChronoStatus() {
        return new ChronoStatusResponse(chronoService.getLastRunDate());
    }

    @PostMapping("chrono/run")
    public ResponseEntity<?> runChrono() {
        try {
            ChronoService.ChronoResult result = chronoService.runAllJobs();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[runChrono] failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    record ChronoStatusResponse(String lastRunDate) {}
}
