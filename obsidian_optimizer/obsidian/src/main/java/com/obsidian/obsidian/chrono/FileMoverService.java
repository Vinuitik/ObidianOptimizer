package com.obsidian.obsidian.chrono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;

@Component
public class FileMoverService {

    private static final Logger log = LoggerFactory.getLogger(FileMoverService.class);

    private static final Set<String> IMAGE_EXTS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");
    private static final Set<String> PDF_EXTS   = Set.of(".pdf");
    private static final Set<String> VIDEO_EXTS = Set.of(".mp4", ".mov", ".mkv");

    public int run(String vaultRoot) {
        Path root   = Paths.get(vaultRoot);
        Path imgDir = root.resolve("resources/images");
        Path pdfDir = root.resolve("resources/pdf");
        Path vidDir = root.resolve("resources/videos");

        try {
            Files.createDirectories(imgDir);
            Files.createDirectories(pdfDir);
            Files.createDirectories(vidDir);
        } catch (IOException e) {
            log.error("[FileMover] Failed to create resource dirs: {}", e.getMessage());
            return 0;
        }

        int moved = 0;
        try (var stream = Files.list(root)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                String lower = file.getFileName().toString().toLowerCase();
                int dot = lower.lastIndexOf('.');
                if (dot < 0) continue;
                String ext = lower.substring(dot);

                Path target;
                if (IMAGE_EXTS.contains(ext))      target = imgDir;
                else if (PDF_EXTS.contains(ext))   target = pdfDir;
                else if (VIDEO_EXTS.contains(ext)) target = vidDir;
                else continue;

                try {
                    Files.move(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    log.info("[FileMover] {} → resources/{}", file.getFileName(), target.getFileName());
                    moved++;
                } catch (IOException e) {
                    log.warn("[FileMover] Could not move {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[FileMover] Failed to list vault root: {}", e.getMessage());
        }

        log.info("[FileMover] Moved {} file(s).", moved);
        return moved;
    }
}
