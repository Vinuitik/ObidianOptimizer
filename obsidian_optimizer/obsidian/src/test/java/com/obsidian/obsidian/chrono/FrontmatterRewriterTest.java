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
    void read_srDueInBodyOnly_returnsNull_bodyIsNotFrontmatter() throws IOException {
        Path f = writeFile("body-only.md",
            "# Notes on spaced repetition\n\nThe plugin stores sr-due: 2025-01-01 in frontmatter.\n");
        assertThat(FrontmatterRewriter.read(f)).isNull();
    }

    @Test
    void read_ignoresSrLinesInBody_whenFrontmatterAlsoPresent() throws IOException {
        Path f = writeFile("both.md",
            "---\nsr-due: 2025-03-15\nsr-interval: 7\nsr-ease: 250\n---\n\n"
            + "```\nsr-due: 1999-01-01\nsr-interval: 99\n```\n");
        var fields = FrontmatterRewriter.read(f);
        assertThat(fields).isNotNull();
        assertThat(fields.due()).isEqualTo(LocalDate.of(2025, 3, 15));
        assertThat(fields.interval()).isEqualTo(7);
    }

    @Test
    void write_neverTouchesSrLinesInBody() throws IOException {
        String body = "```yaml\nsr-due: 1999-01-01\nsr-ease: 111\n```";
        Path f = writeFile("guarded.md",
            "---\nsr-due: 2024-01-01\nsr-interval: 3\nsr-ease: 200\n---\n\n" + body + "\n");
        FrontmatterRewriter.write(f, new FrontmatterRewriter.SrFields(LocalDate.of(2026, 6, 10), 14, 300));
        String result = Files.readString(f);
        assertThat(result).contains(body);                   // body untouched
        assertThat(result).contains("sr-due: 2026-06-10");   // frontmatter updated
    }

    @Test
    void write_fileWithoutFrontmatter_isLeftUnchanged() throws IOException {
        String content = "No frontmatter here.\nsr-due: 2020-01-01 mentioned in prose.\n";
        Path f = writeFile("no-fm.md", content);
        FrontmatterRewriter.write(f, new FrontmatterRewriter.SrFields(LocalDate.of(2026, 1, 1), 3, 200));
        assertThat(Files.readString(f)).isEqualTo(content);
    }

    // ── FSRS mirror ─────────────────────────────────────────────────────────

    @Test
    void readFsrs_returnsNull_whenOnlyLegacyFieldsPresent() throws IOException {
        Path f = writeFile("legacy.md", "---\nsr-due: 2026-06-01\nsr-interval: 3\nsr-ease: 200\n---\n");
        assertThat(FrontmatterRewriter.readFsrs(f)).isNull();  // no fsrs-s → start fresh
    }

    @Test
    void writeFsrs_insertsMissingKeys_andPreservesLegacyEase() throws IOException {
        Path f = writeFile("seed.md",
            "---\ntitle: x\nsr-due: 2026-06-01\nsr-interval: 3\nsr-ease: 250\n---\n\nBody.");
        FrontmatterRewriter.writeFsrs(f, new FrontmatterRewriter.FsrsFields(
            LocalDate.of(2026, 6, 26), 14, 13.8269, 2.1112, LocalDate.of(2026, 6, 12), 1.2, "dEasy:sMid"));
        String result = Files.readString(f);
        assertThat(result).contains("fsrs-s: 13.826900");
        assertThat(result).contains("fsrs-d: 2.111200");
        assertThat(result).contains("fsrs-last: 2026-06-12");
        assertThat(result).contains("fsrs-arm: 1.200000");
        assertThat(result).contains("fsrs-bucket: dEasy:sMid");
        assertThat(result).contains("sr-due: 2026-06-26");   // existing key overwritten
        assertThat(result).contains("sr-ease: 250");          // legacy field untouched
        assertThat(result).contains("title: x");
        assertThat(result).contains("Body.");
    }

    @Test
    void writeFsrs_roundTrips_throughReadFsrs() throws IOException {
        Path f = writeFile("rt.md", "---\nsr-due: 2026-06-01\nsr-interval: 3\n---\n");
        var fields = new FrontmatterRewriter.FsrsFields(
            LocalDate.of(2026, 7, 1), 20, 39.174976, 2.104331, LocalDate.of(2026, 6, 11), 0.85, "dMid:sLong");
        FrontmatterRewriter.writeFsrs(f, fields);
        var back = FrontmatterRewriter.readFsrs(f);
        assertThat(back).isNotNull();
        assertThat(back.due()).isEqualTo(fields.due());
        assertThat(back.interval()).isEqualTo(20);
        assertThat(back.stability()).isEqualTo(39.174976);
        assertThat(back.difficulty()).isEqualTo(2.104331);
        assertThat(back.lastReview()).isEqualTo(fields.lastReview());
        assertThat(back.arm()).isEqualTo(0.85);
        assertThat(back.bucket()).isEqualTo("dMid:sLong");
    }

    @Test
    void writeFsrs_overwritesExistingFsrsKeys_withoutDuplicating() throws IOException {
        Path f = writeFile("upd.md",
            "---\nsr-due: 2026-06-01\nsr-interval: 3\nfsrs-s: 1.000000\nfsrs-d: 5.000000\n"
            + "fsrs-arm: 1.000000\nfsrs-bucket: dEasy:sShort\n---\n");
        FrontmatterRewriter.writeFsrs(f, new FrontmatterRewriter.FsrsFields(
            LocalDate.of(2026, 6, 20), 19, 18.5, 3.2, LocalDate.of(2026, 6, 12), 1.5, "dMid:sMid"));
        String result = Files.readString(f);
        assertThat(result.split("fsrs-s:", -1)).hasSize(2);     // exactly one fsrs-s line
        assertThat(result).contains("fsrs-s: 18.500000");
        assertThat(result).contains("fsrs-bucket: dMid:sMid");
        assertThat(result).doesNotContain("dEasy:sShort");
    }

    @Test
    void writeFsrs_crlfPreserved() throws IOException {
        Path f = writeFile("crlf-fsrs.md", "---\r\nsr-due: 2026-06-01\r\nsr-interval: 3\r\n---\r\n");
        FrontmatterRewriter.writeFsrs(f, new FrontmatterRewriter.FsrsFields(
            LocalDate.of(2026, 6, 20), 19, 18.5, 3.2, LocalDate.of(2026, 6, 12), 1.5, "dMid:sMid"));
        String result = Files.readString(f);
        assertThat(result).contains("\r\n");
        assertThat(result).contains("fsrs-s: 18.500000");
    }

    @Test
    void writeFsrs_fileWithoutFrontmatter_isLeftUnchanged() throws IOException {
        String content = "No frontmatter.\n";
        Path f = writeFile("no-fm-fsrs.md", content);
        boolean wrote = FrontmatterRewriter.writeFsrs(f, new FrontmatterRewriter.FsrsFields(
            LocalDate.of(2026, 6, 20), 19, 18.5, 3.2, null, null, null));
        assertThat(wrote).isFalse();
        assertThat(Files.readString(f)).isEqualTo(content);
    }
}
