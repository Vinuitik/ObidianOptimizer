package com.obsidian.obsidian.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public liveness probe (no auth; nginx strips /api so it is public as /api/health) — the desktop launcher polls it. */
@RestController
public class HealthController {
    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }
}
