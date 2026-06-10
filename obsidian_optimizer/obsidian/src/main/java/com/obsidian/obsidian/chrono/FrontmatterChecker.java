package com.obsidian.obsidian.chrono;

import java.nio.file.Path;

@FunctionalInterface
public interface FrontmatterChecker {
    boolean needsFix(Path file);
}
