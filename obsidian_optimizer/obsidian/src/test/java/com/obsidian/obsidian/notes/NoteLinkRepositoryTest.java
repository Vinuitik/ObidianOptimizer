package com.obsidian.obsidian.notes;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NoteLinkRepositoryTest {

    @Test
    void extractTargets_simpleLink() {
        Set<String> targets = NoteLinkRepository.extractTargets("See [[NoteA]] for more.");
        assertThat(targets).containsExactly("NoteA");
    }

    @Test
    void extractTargets_displayTextLink() {
        Set<String> targets = NoteLinkRepository.extractTargets("See [[NoteA|click here]].");
        assertThat(targets).containsExactly("NoteA");
    }

    @Test
    void extractTargets_pathPrefixLink_extractsBasename() {
        Set<String> targets = NoteLinkRepository.extractTargets("See [[Folder/SubNoteB]].");
        assertThat(targets).containsExactly("SubNoteB");
    }

    @Test
    void extractTargets_pathPrefixWithDisplayText() {
        Set<String> targets = NoteLinkRepository.extractTargets("[[Folder/NoteC|alias]]");
        assertThat(targets).containsExactly("NoteC");
    }

    @Test
    void extractTargets_multipleLinks() {
        Set<String> targets = NoteLinkRepository.extractTargets("[[A]] and [[B]] and [[C]].");
        assertThat(targets).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void extractTargets_duplicateLinksDeduped() {
        Set<String> targets = NoteLinkRepository.extractTargets("[[A]] then [[A]] again.");
        assertThat(targets).containsExactly("A");
    }

    @Test
    void extractTargets_noLinksReturnsEmptySet() {
        assertThat(NoteLinkRepository.extractTargets("Plain text, no links.")).isEmpty();
    }

    @Test
    void extractTargets_emptyStringReturnsEmptySet() {
        assertThat(NoteLinkRepository.extractTargets("")).isEmpty();
    }

    @Test
    void extractTargets_linkInFrontmatter_stillExtracted() {
        String content = "---\nsr-due: 2025-01-01\n---\n\n[[LinkedNote]] is referenced.";
        assertThat(NoteLinkRepository.extractTargets(content)).contains("LinkedNote");
    }

    @Test
    void rewriteLinks_simpleLink() {
        String result = NoteLinkRepository.rewriteLinks("See [[OldName]] here.", "OldName", "NewName");
        assertThat(result).isEqualTo("See [[NewName]] here.");
    }

    @Test
    void rewriteLinks_linkWithDisplayText() {
        String result = NoteLinkRepository.rewriteLinks("[[OldName|alias]]", "OldName", "NewName");
        assertThat(result).isEqualTo("[[NewName|alias]]");
    }

    @Test
    void rewriteLinks_linkWithPathPrefix() {
        String result = NoteLinkRepository.rewriteLinks("[[Folder/OldName]]", "OldName", "NewName");
        assertThat(result).isEqualTo("[[Folder/NewName]]");
    }

    @Test
    void rewriteLinks_linkWithPathPrefixAndDisplayText() {
        String result = NoteLinkRepository.rewriteLinks("[[Folder/OldName|text]]", "OldName", "NewName");
        assertThat(result).isEqualTo("[[Folder/NewName|text]]");
    }

    @Test
    void rewriteLinks_multipleOccurrencesAllReplaced() {
        String result = NoteLinkRepository.rewriteLinks(
            "[[OldName]] first, [[OldName]] second.", "OldName", "NewName");
        assertThat(result).isEqualTo("[[NewName]] first, [[NewName]] second.");
    }

    @Test
    void rewriteLinks_doesNotTouchOtherLinks() {
        String result = NoteLinkRepository.rewriteLinks(
            "[[OldName]] and [[SomethingElse]].", "OldName", "NewName");
        assertThat(result).isEqualTo("[[NewName]] and [[SomethingElse]].");
    }

    @Test
    void rewriteLinks_noMatchReturnsSameString() {
        String content = "[[SomethingElse]] with no match.";
        assertThat(NoteLinkRepository.rewriteLinks(content, "OldName", "NewName"))
            .isEqualTo(content);
    }

    @Test
    void rewriteLinks_closingBracketAfterLink_preserved() {
        String result = NoteLinkRepository.rewriteLinks("before [[OldName]] after", "OldName", "NewName");
        assertThat(result).isEqualTo("before [[NewName]] after");
    }

    // ── Heading anchors: [[Note#section]] ─────────────────────────────────────

    @Test
    void extractTargets_headingAnchor_targetsTheNote() {
        assertThat(NoteLinkRepository.extractTargets("See [[NoteA#Methods]]."))
            .containsExactly("NoteA");
    }

    @Test
    void extractTargets_headingAnchorWithPathAndAlias() {
        assertThat(NoteLinkRepository.extractTargets("[[Folder/NoteB#Results|the results]]"))
            .containsExactly("NoteB");
    }

    @Test
    void extractTargets_selfHeadingLink_ignored() {
        // [[#Heading]] links within the same note — no external target
        assertThat(NoteLinkRepository.extractTargets("Jump to [[#Conclusion]].")).isEmpty();
    }

    @Test
    void rewriteLinks_preservesHeadingAnchor() {
        String result = NoteLinkRepository.rewriteLinks("see [[OldName#Setup]] first", "OldName", "NewName");
        assertThat(result).isEqualTo("see [[NewName#Setup]] first");
    }

    @Test
    void rewriteLinks_headingAnchorWithAlias() {
        String result = NoteLinkRepository.rewriteLinks("[[OldName#Setup|setup docs]]", "OldName", "NewName");
        assertThat(result).isEqualTo("[[NewName#Setup|setup docs]]");
    }

    @Test
    void rewriteLinks_prefixNameWithHeading_notConfusedWithSubstring() {
        // "OldNameExtended" must not be rewritten when renaming "OldName"
        String content = "[[OldNameExtended#Setup]] and [[OldName#Setup]]";
        String result = NoteLinkRepository.rewriteLinks(content, "OldName", "NewName");
        assertThat(result).isEqualTo("[[OldNameExtended#Setup]] and [[NewName#Setup]]");
    }
}
