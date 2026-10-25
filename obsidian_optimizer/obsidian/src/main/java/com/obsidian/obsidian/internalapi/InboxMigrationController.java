package com.obsidian.obsidian.internalapi;

import com.obsidian.obsidian.inbox.InboxFolderMigration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Manual trigger for the one-time _inbox folder cleanup (see InboxFolderMigration). Internal token. */
@RestController
@RequestMapping("/api/internal/inbox")
public class InboxMigrationController {

    private final InboxFolderMigration migration;

    @Value("${mcp.api.token:}")
    private String internalToken;

    public InboxMigrationController(InboxFolderMigration migration) { this.migration = migration; }

    /** POST /api/internal/inbox/migrate-folders?dryRun=true (default true; pass dryRun=false to apply). */
    @PostMapping("/migrate-folders")
    public ResponseEntity<?> migrate(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                     @RequestParam(defaultValue = "true") boolean dryRun) {
        if (internalToken == null || internalToken.isBlank() || token == null
                || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                                          token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad internal token");
        }
        try {
            return ResponseEntity.ok(migration.run(dryRun));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}
