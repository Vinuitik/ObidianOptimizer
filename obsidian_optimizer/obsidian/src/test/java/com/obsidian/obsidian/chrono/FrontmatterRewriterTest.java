package com.obsidian.obsidian.chrono;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FrontmatterRewriterTest {

    @TempDir Path tmp;

    private Path writeFile(String name, String content) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    @Test
    void read_parsesAllThreeFields() throws IOException {
        Path f = writeFile("note.md",
            "---\nsr-due: 2025-03-15\nsr-interval: 7\nsr-ease: 250\n---\n\nBody.");
        var fields = FrontmatterRewriter.read(f);
        assertThat(fields).isNotNull();
        assertThat(fields.due()).isEqualTo(LocalDate.of(2025, 3, 15));
        assertThat(fields.interval()).isEqualTo(7);
        assertThat(fields.ease()).isEqualTo(250);
    }

    @Test
    void read_crlfLineEndings() throws IOException {
        Path f = writeFile("crlf.md",
            "---\r\nsr-due: 2025-01-01\r\nsr-interval: 5\r\nsr-ease: 230\r\n---\r\n");
        var fields = FrontmatterRewriter.read(f);
        assertThat(fields).isNotNull();
        assertThat(fields.due()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(fields.interval()).isEqualTo(5);
        assertThat(fields.ease()).isEqualTo(230);
    }

    @Test
    void read_noSrDueReturnsNull() throws IOException {
        Path f = writeFile("no-due.md", "---\nsr-interval: 3\nsr-ease: 200\n---\n\nBody.");
        assertThat(FrontmatterRewriter.read(f)).isNull();
    }

    @Test
    void read_noFrontmatterReturnsNull() throws IOException {
        Path f = writeFile("plain.md", "# Just a heading\n\nNo frontmatter.");
        assertThat(FrontmatterRewriter.read(f)).isNull();
    }

    @Test
    void read_missingIntervalAndEaseDefaultToFallbackValues() throws IOException {
        Path f = writeFile("minimal.md", "---\nsr-due: 2025-06-01\n---\n");
        var fields = FrontmatterRewriter.read(f);
        assertThat(fields).isNotNull();
        assertThat(fields.interval()).isEqualTo(3);
        assertThat(fields.ease()).isEqualTo(200);
    }

    @Test
    void read_malformedDueDateReturnsNull() throws IOException {
        Path f = writeFile("bad-date.md", "---\nsr-due: not-a-date\nsr-interval: 3\n---\n");
        assertThat(FrontmatterRewriter.read(f)).isNull();
    }

    @Test
    void write_updatesAllThreeFields() throws IOException {
        Path f = writeFile("w.md",
            "---\nsr-due: 2024-01-01\nsr-interval: 3\nsr-ease: 200\n---\n\nBody text.");
        FrontmatterRewriter.write(f, new FrontmatterRewriter.SrFields(LocalDate.of(2026, 6, 10), 14, 300));
        String result = Files.readString(f);
        assertThat(result).contains("sr-due: 2026-06-10");
        assertThat(result).contains("sr-interval: 14");
        assertThat(result).contains("sr-ease: 300");
    }

    @Test
    void write_preservesBodyAndOtherFrontmatterLines() throws IOException {
        Path f = writeFile("extra.md",
            "---\ntags: review\nsr-due: 2024-01-01\nsr-interval: 3\nsr-ease: 200\n---\n\nMy body.");
        FrontmatterRewriter.write(f, new FrontmatterRewriter.SrFields(LocalDate.of(2026, 1, 1), 5, 220));
        String result = Files.readString(f);
        assertThat(result).contains("tags: review");
        assertThat(result).contains("My body.");
        assertThat(result).contains("sr-due: 2026-01-01");
    }

    @Test
    void write_preservesCrlfLineEndings() throws IOException {
        Path f = writeFile("crlf.md",
            "---\r\nsr-due: 2024-01-01\r\nsr-interval: 3\r\nsr-ease: 200\r\n---\r\n");
        FrontmatterRewriter.write(f, new FrontmatterRewriter.SrFields(LocalDate.of(2026, 5, 5), 7, 250));
        String result = Files.readString(f);
        assertThat(result).contains("\r\n");
        assertThat(result).doesNotContain("2024-01-01");
        assertThat(result).contains("sr-due: 2026-05-05");
    }

    @Test
    void write_roundTrip_readAfterWriteMatchesNewValues() throws IOException {
        Path f = writeFile("roundtrip.md",
            "---\nsr-due: 2024-01-01\nsr-interval: 3\nsr-ease: 200\n---\n");
        var newFields = new FrontmatterRewriter.SrFields(LocalDate.of(2027, 3, 15), 21, 280);
        FrontmatterRewriter.write(f, newFields);
        var readBack = FrontmatterRewriter.read(f);
        assertThat(readBack).isNotNull();
        assertThat(readBack.due()).isEqualTo(newFields.due());
        assertThat(readBack.interval()).isEqualTo(newFields.interval());
        assertThat(readBack.ease()).isEqualTo(newFields.ease());
    }

    @Test
    void hasInvalidDate_detectsCorruptionOnLine2() throws IOException {
        Path f = writeFile("corrupt.md",
            "---\nsr-due: Invalid date\nsr-interval: 3\nsr-ease: 200\n---\n\nBody.");
        assertThat(FrontmatterRewriter.hasInvalidDate(f)).isTrue();
    }

    @Test
    void hasInvalidDate_cleanFileReturnsFalse() throws IOException {
        Path f = writeFile("clean.md",
            "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n");
        assertThat(FrontmatterRewriter.hasInvalidDate(f)).isFalse();
    }

    @Test
    void hasInvalidDate_singleLineFileReturnsFalse() throws IOException {
        Path f = writeFile("short.md", "---");
        assertThat(FrontmatterRewriter.hasInvalidDate(f)).isFalse();
    }

    @Test
    void hasInvalidDate_invalidDateOnLine2WithCrlf() throws IOException {
        Path f = writeFile("crlf-corrupt.md",
            "---\r\nsr-due: Invalid date\r\nsr-interval: 3\r\n---\r\n");
        assertThat(FrontmatterRewriter.hasInvalidDate(f)).isTrue();
    }
}
