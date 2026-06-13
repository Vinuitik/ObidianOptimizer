package com.obsidian.obsidian.ml;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownPreprocessor {

    private static final int MAX_CHUNK_LENGTH = 1000;
    private static final int OVERLAP = 200;

    public static class PreprocessedNote {
        private final String cleanText;
        private final List<String> imageRefs;

        public PreprocessedNote(String cleanText, List<String> imageRefs) {
            this.cleanText = cleanText;
            this.imageRefs = imageRefs;
        }

        public String getCleanText() {
            return cleanText;
        }

        public List<String> getImageRefs() {
            return imageRefs;
        }
    }

    /**
     * Cleans raw markdown (strips yaml frontmatter, html comments, etc) and extracts image references.
     */
    public PreprocessedNote process(String rawMarkdown) {
        if (rawMarkdown == null || rawMarkdown.trim().isEmpty()) {
            return new PreprocessedNote("", new ArrayList<>());
        }

        List<String> imageRefs = new ArrayList<>();
        String text = rawMarkdown;

        // 1. Strip YAML Frontmatter
        text = text.replaceAll("(?s)^---\\s*\\n.*?\\n---\\s*\\n?", "");

        // 2. Strip HTML comments
        text = text.replaceAll("(?s)<!--.*?-->", "");

        // 3. Extract and replace images (wiki-style and standard markdown)
        Pattern wikiImgPattern = Pattern.compile("!\\[\\[(.*?)]]");
        Matcher wikiImgMatcher = wikiImgPattern.matcher(text);
        while (wikiImgMatcher.find()) {
            imageRefs.add(wikiImgMatcher.group(1));
        }
        text = wikiImgMatcher.replaceAll("");

        Pattern mdImgPattern = Pattern.compile("!\\[.*?]\\((.*?)\\)");
        Matcher mdImgMatcher = mdImgPattern.matcher(text);
        while (mdImgMatcher.find()) {
            imageRefs.add(mdImgMatcher.group(1));
        }
        text = mdImgMatcher.replaceAll("");

        // 4. Clean wiki-links [[Folder/Note|Alias]] -> Alias
        text = text.replaceAll("\\[\\[(?:[^|\\]]+\\|)?([^|\\]]+)]]", "$1");

        // 5. Clean standalone tags at the end of lines
        text = text.replaceAll("(?m)\\s*#[a-zA-Z0-9_/-]+\\s*$", "");

        // 6. Collapse multiple newlines into a standard double newline (paragraph boundary)
        text = text.replaceAll("\\n{3,}", "\n\n").trim();

        return new PreprocessedNote(text, imageRefs);
    }

    /**
     * Structurally chunks the text based on headings, falling back to sliding window paragraphs.
     */
    public List<NoteChunk> chunkNote(String notePath, String rawMarkdown) {
        PreprocessedNote processed = process(rawMarkdown);
        String text = processed.getCleanText();
        List<NoteChunk> chunks = new ArrayList<>();

        if (text.isEmpty()) {
            // Keep a chunk for image context if images exist
            if (!processed.getImageRefs().isEmpty()) {
                chunks.add(new NoteChunk(notePath, 0, "", processed.getImageRefs()));
            }
            return chunks;
        }

        // Semantic split by Headings (##, ###)
        String[] sections = text.split("(?m)^#{1,6}\\s+");
        int chunkIndex = 0;

        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.length() <= MAX_CHUNK_LENGTH) {
                chunks.add(new NoteChunk(notePath, chunkIndex++, trimmed, processed.getImageRefs()));
            } else {
                // Split large sections by paragraphs
                String[] paragraphs = trimmed.split("\\n\\n");
                StringBuilder currentChunk = new StringBuilder();

                for (String p : paragraphs) {
                    if (currentChunk.length() + p.length() > MAX_CHUNK_LENGTH && currentChunk.length() > 0) {
                        chunks.add(new NoteChunk(notePath, chunkIndex++, currentChunk.toString().trim(), processed.getImageRefs()));
                        
                        // Sliding window overlap
                        int overlapStart = Math.max(0, currentChunk.length() - OVERLAP);
                        currentChunk = new StringBuilder(currentChunk.substring(overlapStart));
                    }
                    currentChunk.append(p).append("\n\n");
                }
                if (currentChunk.length() > 0) {
                    chunks.add(new NoteChunk(notePath, chunkIndex++, currentChunk.toString().trim(), processed.getImageRefs()));
                }
            }
        }
        return chunks;
    }
}
