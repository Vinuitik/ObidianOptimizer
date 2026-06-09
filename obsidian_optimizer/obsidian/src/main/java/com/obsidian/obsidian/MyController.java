package com.obsidian.obsidian;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.obsidian.obsidian.FileRepository.ChildrenResult;
import com.obsidian.obsidian.FileRepository.ReviewPage;

@RestController
public class MyController {

    private static final Logger log = LoggerFactory.getLogger(MyController.class);

    private final FileRepository repository;

    MyController(FileRepository repository) {
        this.repository = repository;
    }

    // ── Read endpoints (public) ──────────────────────────────────────────────

    @GetMapping("names")
    public ArrayList<String> getNames() {
        return repository.getNoteNames();
    }

    // Returns immediate children of a folder (lazy tree loading).
    // No folder param = vault root.
    @GetMapping("children")
    public ChildrenResult getChildren(@RequestParam(required = false) String folder) {
        String path = (folder == null || folder.isBlank())
                ? repository.getRootPath()
                : folder;
        return repository.getDirectChildren(path);
    }

    // Returns paginated review-due notes.
    // offset defaults to 0, limit defaults to 40.
    @GetMapping("review")
    public ReviewPage getReviewNames(
            @RequestParam(defaultValue = "0")  int offset,
            @RequestParam(defaultValue = "40") int limit) {
        return repository.getReviewNotesPaged(offset, limit);
    }

    @GetMapping("text")
    public String getText(@RequestParam String noteName) {
        String content = repository.getText(noteName);
        log.debug("[getText] path={} responseBytes={}", noteName, content == null ? 0 : content.length());
        return content;
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    @GetMapping("me")
    public String getMe(Principal principal) {
        return principal.getName();
    }

    // ── Write endpoints (require session auth) ───────────────────────────────

    @PostMapping("notes")
    public ResponseEntity<?> createNote(@RequestBody CreateNoteRequest req) {
        log.info("[createNote] folder={} name={}", req.folder(), req.name());
        try {
            String path = repository.createNote(req.folder(), req.name());
            log.info("[createNote] created path={}", path);
            return ResponseEntity.ok(Map.of("path", path));
        } catch (IOException e) {
            log.error("[createNote] failed folder={} name={}: {}", req.folder(), req.name(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("notes")
    public ResponseEntity<?> updateNote(@RequestBody UpdateNoteRequest req) {
        try {
            repository.updateNote(req.path(), req.content());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("notes/content")
    public ResponseEntity<?> patchNote(@RequestBody PatchNoteRequest req) {
        log.info("[patchNote] path={} hunks={}", req.path(), req.hunks().size());
        try {
            repository.patchNote(req.path(), req.hunks());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            log.error("[patchNote] failed path={}: {}", req.path(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("notes/rename")
    public ResponseEntity<?> renameNote(@RequestBody RenameNoteRequest req) {
        log.info("[renameNote] oldPath={} newName={}", req.oldPath(), req.newName());
        try {
            String newPath = repository.renameNote(req.oldPath(), req.newName());
            log.info("[renameNote] renamed to {}", newPath);
            return ResponseEntity.ok(Map.of("path", newPath));
        } catch (IOException e) {
            log.error("[renameNote] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("notes")
    public ResponseEntity<?> deleteNote(@RequestBody DeleteNoteRequest req) {
        log.info("[deleteNote] path={}", req.path());
        try {
            repository.softDeleteNote(req.path());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            log.error("[deleteNote] failed path={}: {}", req.path(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record CreateNoteRequest(String folder, String name) {}
    record UpdateNoteRequest(String path, String content) {}
    record PatchNoteRequest(String path, List<FileRepository.PatchHunk> hunks) {}
    record RenameNoteRequest(String oldPath, String newName) {}
    record DeleteNoteRequest(String path) {}
}
