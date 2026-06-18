package com.obsidian.obsidian.workspace;

import com.obsidian.obsidian.settings.SettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/workspace")
public class WorkspaceController {

    private static final Set<String> VIDEO_EXTS = Set.of(".mp4", ".mov", ".mkv", ".webm", ".avi");
    private static final Set<String> AUDIO_EXTS = Set.of(".mp3", ".wav", ".ogg", ".m4a", ".flac");
    private static final Set<String> PDF_EXTS   = Set.of(".pdf");

    private final SettingsRepository settingsRepo;

    public WorkspaceController(SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    record WorkspaceFile(String name, String type) {}

    @GetMapping("/files")
    public ResponseEntity<List<WorkspaceFile>> listFiles() {
        Path dir = Paths.get(settingsRepo.getVaultPath()).resolve("_workspace");
        if (!Files.isDirectory(dir)) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        try (var stream = Files.list(dir)) {
            List<WorkspaceFile> files = stream
                    .filter(Files::isRegularFile)
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return new WorkspaceFile(name, typeFor(name));
                    })
                    .filter(f -> !f.type().equals("other"))
                    .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(files);
        } catch (IOException e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    private static String typeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "other";
        String ext = filename.substring(dot).toLowerCase();
        if (VIDEO_EXTS.contains(ext)) return "video";
        if (AUDIO_EXTS.contains(ext)) return "audio";
        if (PDF_EXTS.contains(ext))   return "pdf";
        return "other";
    }
}
