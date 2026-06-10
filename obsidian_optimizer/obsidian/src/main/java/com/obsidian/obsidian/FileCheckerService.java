package com.obsidian.obsidian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

@Component
public class FileCheckerService {

    private static final Logger log = LoggerFactory.getLogger(FileCheckerService.class);

    // Walks the pre-collected md file list, runs the checker on each, and resets
    // frontmatter to {today+3, interval=3, ease=200} for any file that needs fixing.
    // Returns count of files fixed.
    public int run(List<Path> mdFiles, FrontmatterChecker checker) {
        LocalDate resetDue = LocalDate.now().plusDays(3);
        FrontmatterRewriter.SrFields resetFields = new FrontmatterRewriter.SrFields(resetDue, 3, 200);
        int fixed = 0;

        for (Path file : mdFiles) {
            try {
                if (checker.needsFix(file)) {
                    FrontmatterRewriter.write(file, resetFields);
                    log.info("[FileChecker] Fixed {}", file.getFileName());
                    fixed++;
                }
            } catch (IOException e) {
                log.warn("[FileChecker] Could not fix {}: {}", file.getFileName(), e.getMessage());
            }
        }

        log.info("[FileChecker] Fixed {} file(s).", fixed);
        return fixed;
    }
}
