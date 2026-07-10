package com.obsidian.obsidian.notes;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotesController {

    private static final Logger log = LoggerFactory.getLogger(NotesController.class);

    private final FileRepository repository;

    NotesController(FileRepository repository) {
        this.repository = repository;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @GetMapping("names")
    public ArrayList<String> getNames() {
        return repository.getNoteNames();
    }

    @GetMapping("children")
    public FileRepository.ChildrenResult getChildren(@RequestParam(required = false) String folder) {
        String path = (folder == null || folder.isBlank())
                ? repository.getRootPath()
                : folder;
        return repository.getDirectChildren(path);
    }

    @GetMapping("review")
    public FileRepository.ReviewPageInfo getReviewNames(
            @RequestParam(defaultValue = "0")  int offset,
            @RequestParam(defaultValue = "40") int limit) {
        // Each note carries hasCards so the client can split the daily set into
        // flashcard vs read tracks under the caps (see reviewPlan.js). The caps
        // themselves ride the /settings sync (maxDailyReviews / maxDailyFlashcards).
        return repository.getReviewNotesPagedWithCards(offset, limit);
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

    // ── Write ─────────────────────────────────────────────────────────────────

    @PostMapping("folders")
    public ResponseEntity<?> createFolder(@RequestBody CreateFolderRequest req) {
        log.info("[createFolder] parent={} name={}", req.parentPath(), req.name());
        try {
            String path = repository.createFolder(req.parentPath(), req.name());
            return ResponseEntity.ok(Map.of("path", path));
        } catch (IOException e) {
            log.error("[createFolder] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

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

    @PatchMapping("notes/move")
    public ResponseEntity<?> moveNote(@RequestBody MoveNoteRequest req) {
        log.info("[moveNote] source={} target={}", req.sourcePath(), req.targetFolder());
        try {
            String newPath = repository.moveNote(req.sourcePath(), req.targetFolder());
            return ResponseEntity.ok(Map.of("path", newPath));
        } catch (IOException e) {
            log.error("[moveNote] failed: {}", e.getMessage());
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

    @DeleteMapping("folders")
    public ResponseEntity<?> deleteFolder(@RequestBody DeleteFolderRequest req) {
        log.info("[deleteFolder] path={}", req.path());
        try {
            repository.softDeleteFolder(req.path());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            log.error("[deleteFolder] failed path={}: {}", req.path(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record CreateFolderRequest(String parentPath, String name) {}
    record DeleteFolderRequest(String path) {}
    record CreateNoteRequest(String folder, String name) {}
    record UpdateNoteRequest(String path, String content) {}
    record PatchNoteRequest(String path, List<FileRepository.PatchHunk> hunks) {}
    record RenameNoteRequest(String oldPath, String newName) {}
    record DeleteNoteRequest(String path) {}
    record MoveNoteRequest(String sourcePath, String targetFolder) {}
}
