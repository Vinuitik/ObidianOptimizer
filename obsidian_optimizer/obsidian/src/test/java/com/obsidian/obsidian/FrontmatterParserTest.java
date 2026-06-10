package com.obsidian.obsidian;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FrontmatterParserTest {

    @Test
    void parsesAllThreeFields() {
        String raw = "---\nsr-due: 2024-06-15\nsr-interval: 7\nsr-ease: 250\n---\n\nBody.";
        var m = FrontmatterParser.parse(raw);
        assertThat(m.srDue()).isEqualTo(LocalDate.of(2024, 6, 15));
        assertThat(m.srInterval()).isEqualTo(7);
        assertThat(m.srEase()).isEqualTo(250);
    }

    @Test
    void handlesCrlfLineEndings() {
        String raw = "---\r\nsr-due: 2024-01-01\r\nsr-interval: 3\r\n---\r\n";
        var m = FrontmatterParser.parse(raw);
        assertThat(m.srDue()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(m.srInterval()).isEqualTo(3);
    }

    @Test
    void missingFieldsAreNull() {
        String raw = "---\nsr-due: 2024-01-01\n---\n";
        var m = FrontmatterParser.parse(raw);
        assertThat(m.srInterval()).isNull();
        assertThat(m.srEase()).isNull();
    }

    @Test
    void noFrontmatterReturnsAllNull() {
        var m = FrontmatterParser.parse("# Just a heading\n\nNo frontmatter.");
        assertThat(m.srDue()).isNull();
        assertThat(m.srInterval()).isNull();
        assertThat(m.srEase()).isNull();
    }

    @Test
    void unclosedFrontmatterReturnsAllNull() {
        var m = FrontmatterParser.parse("---\nsr-due: 2024-01-01\n");
        assertThat(m.srDue()).isNull();
    }

    @Test
    void nullInputReturnsAllNull() {
        var m = FrontmatterParser.parse(null);
        assertThat(m.srDue()).isNull();
        assertThat(m.srInterval()).isNull();
        assertThat(m.srEase()).isNull();
    }

    @Test
    void malformedDateIsIgnoredOtherFieldsStillParsed() {
        String raw = "---\nsr-due: not-a-date\nsr-interval: 5\n---\n";
        var m = FrontmatterParser.parse(raw);
        assertThat(m.srDue()).isNull();
        assertThat(m.srInterval()).isEqualTo(5);
    }

    @Test
    void malformedIntegerIsIgnoredOtherFieldsStillParsed() {
        String raw = "---\nsr-interval: abc\nsr-ease: 200\n---\n";
        var m = FrontmatterParser.parse(raw);
        assertThat(m.srInterval()).isNull();
        assertThat(m.srEase()).isEqualTo(200);
    }

    @Test
    void extraWhitespaceAroundValueIsTrimmed() {
        String raw = "---\nsr-interval:  42 \n---\n";
        var m = FrontmatterParser.parse(raw);
        assertThat(m.srInterval()).isEqualTo(42);
    }

    @Test
    void linesWithoutColonAreIgnored() {
        String raw = "---\nno-colon-line\nsr-ease: 220\n---\n";
        var m = FrontmatterParser.parse(raw);
        assertThat(m.srEase()).isEqualTo(220);
    }

    @Test
    void valueWithColonInItParsesFirstColonOnly() {
        // e.g. "sr-due: 2024:01:01" — weird but should not crash
        String raw = "---\nsr-due: 2024:01:01\nsr-ease: 180\n---\n";
        var m = FrontmatterParser.parse(raw);
        assertThat(m.srDue()).isNull();        // not a valid ISO date
        assertThat(m.srEase()).isEqualTo(180); // other field still parsed
    }
}
