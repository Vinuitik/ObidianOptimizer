package com.obsidian.obsidian.ml;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownPreprocessorTest {

    @Test
    void stripFrontmatter_removesLeadingYamlBlock_keepsBody() {
        String md = "---\nsr-due: 2026-06-14\nsr-interval: 12\n---\n# Title\n\nBody text.";
        assertThat(MarkdownPreprocessor.stripFrontmatter(md)).isEqualTo("# Title\n\nBody text.");
    }

    @Test
    void stripFrontmatter_sameBodyDifferentFrontmatter_isIdentical() {
        // This is the property body_hash relies on: an sr-due rewrite must not change
        // the stripped body, so the card work-list won't see it as a content change.
        String before = "---\nsr-due: 2026-06-10\n---\n# Note\n\nContent.";
        String after  = "---\nsr-due: 2026-06-14\nsr-interval: 30\n---\n# Note\n\nContent.";
        assertThat(MarkdownPreprocessor.stripFrontmatter(before))
            .isEqualTo(MarkdownPreprocessor.stripFrontmatter(after));
    }

    @Test
    void stripFrontmatter_noFrontmatter_returnsUnchanged() {
        String md = "# Just a note\n\nNo frontmatter here.";
        assertThat(MarkdownPreprocessor.stripFrontmatter(md)).isEqualTo(md);
    }

    @Test
    void stripFrontmatter_null_returnsEmpty() {
        assertThat(MarkdownPreprocessor.stripFrontmatter(null)).isEmpty();
    }
}
