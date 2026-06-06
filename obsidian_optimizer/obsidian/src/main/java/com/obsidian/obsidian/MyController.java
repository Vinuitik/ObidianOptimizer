package com.obsidian.obsidian;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.obsidian.obsidian.FileRepository.ChildrenResult;
import com.obsidian.obsidian.FileRepository.ReviewPage;

@RestController
public class MyController {

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
        return repository.getText(noteName);
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    @GetMapping("me")
    public String getMe(Principal principal) {
        return principal.getName();
    }

    // ── Write endpoints (require session auth) ───────────────────────────────

    @PostMapping("notes")
    public ResponseEntity<?> createNote(@RequestBody CreateNoteRequest req) {
        try {
            String path = repository.createNote(req.folder(), req.name());
            return ResponseEntity.ok(Map.of("path", path));
        } catch (IOException e) {
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

    @PatchMapping("notes/rename")
    public ResponseEntity<?> renameNote(@RequestBody RenameNoteRequest req) {
        try {
            String newPath = repository.renameNote(req.oldPath(), req.newName());
            return ResponseEntity.ok(Map.of("path", newPath));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("notes")
    public ResponseEntity<?> deleteNote(@RequestBody DeleteNoteRequest req) {
        try {
            repository.softDeleteNote(req.path());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record CreateNoteRequest(String folder, String name) {}
    record UpdateNoteRequest(String path, String content) {}
    record RenameNoteRequest(String oldPath, String newName) {}
    record DeleteNoteRequest(String path) {}
}
