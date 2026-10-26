package com.obsidian.obsidian.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** {@code POST import} (nginx strips /api) — session-authenticated like every non-public route. */
@RestController
public class ImportController {
    private static final Logger log = LoggerFactory.getLogger(ImportController.class);
    private final ImportService service;

    public ImportController(ImportService service) { this.service = service; }

    @PostMapping("import")
    public ResponseEntity<?> importNotes(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no files"));
        }
        try {
            return ResponseEntity.ok(service.importFiles(files));
        } catch (ImportRejectedException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[import] failed: {}", e.toString());
            return ResponseEntity.status(500).body(Map.of("error", "import failed: " + e.getMessage()));
        }
    }
}
