package com.obsidian.obsidian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileCheckerServiceTest {

    @TempDir Path tmp;
    FileCheckerService service = new FileCheckerService();

    private Path writeFile(String name, String content) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    @Test
    void fixesFileWhenCheckerReturnsTrue() throws IOException {
        Path f = writeFile("corrupt.md",
            "---\nsr-due: Invalid date\nsr-interval: 3\nsr-ease: 200\n---\n\n#review\n");
        int fixed = service.run(List.of(f), FrontmatterRewriter::hasInvalidDate);
        assertThat(fixed).isEqualTo(1);
        String result = Files.readString(f);
        assertThat(result).contains("sr-due: " + LocalDate.now().plusDays(3));
        assertThat(result).doesNotContain("Invalid date");
    }

    @Test
    void skipsFileWhenCheckerReturnsFalse() throws IOException {
        Path f = writeFile("clean.md",
            "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n\n#review\n");
        int fixed = service.run(List.of(f), FrontmatterRewriter::hasInvalidDate);
        assertThat(fixed).isEqualTo(0);
        assertThat(Files.readString(f)).contains("2025-01-01");
    }

    @Test
    void emptyListReturnsZero() {
        int fixed = service.run(List.of(), path -> true);
        assertThat(fixed).isEqualTo(0);
    }

    @Test
    void handlesMultipleFiles_fixesOnlyTheBadOnes() throws IOException {
        Path good = writeFile("good.md",  "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n");
        Path bad1 = writeFile("bad1.md",  "---\nsr-due: Invalid date\nsr-interval: 3\nsr-ease: 200\n---\n");
        Path bad2 = writeFile("bad2.md",  "---\nsr-due: Invalid date\nsr-interval: 3\nsr-ease: 200\n---\n");
        int fixed = service.run(List.of(good, bad1, bad2), FrontmatterRewriter::hasInvalidDate);
        assertThat(fixed).isEqualTo(2);
        assertThat(Files.readString(good)).contains("2025-01-01");
    }

    @Test
    void customCheckerAlwaysTrueFixesEveryFile() throws IOException {
        Path f = writeFile("any.md",
            "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n");
        int fixed = service.run(List.of(f), path -> true);
        assertThat(fixed).isEqualTo(1);
    }

    @Test
    void resetFieldsAreSetToDefaultValues() throws IOException {
        Path f = writeFile("reset.md",
            "---\nsr-due: Invalid date\nsr-interval: 99\nsr-ease: 999\n---\n");
        service.run(List.of(f), FrontmatterRewriter::hasInvalidDate);
        var fields = FrontmatterRewriter.read(f);
        assertThat(fields).isNotNull();
        assertThat(fields.due()).isEqualTo(LocalDate.now().plusDays(3));
        assertThat(fields.interval()).isEqualTo(3);
        assertThat(fields.ease()).isEqualTo(200);
    }
}
