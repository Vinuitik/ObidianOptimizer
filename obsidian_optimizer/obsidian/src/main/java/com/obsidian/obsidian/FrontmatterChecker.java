package com.obsidian.obsidian;

import java.nio.file.Path;

// Pluggable corruption-detection strategy for FileCheckerService.
// Swap the implementation when the frontmatter format changes.
@FunctionalInterface
public interface FrontmatterChecker {
    boolean needsFix(Path file);
}
