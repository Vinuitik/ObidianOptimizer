package com.obsidian.obsidian.ml;

import java.util.List;

public class NoteChunk {
    private String notePath;
    private int chunkIndex;
    private String source;
    private String text;
    private List<String> imageRefs;

    public NoteChunk() {
    }

    public NoteChunk(String notePath, int chunkIndex, String text, List<String> imageRefs) {
        this.notePath = notePath;
        this.chunkIndex = chunkIndex;
        this.text = text;
        this.imageRefs = imageRefs;
    }

    public NoteChunk(String notePath, int chunkIndex, String source, String text, List<String> imageRefs) {
        this.notePath = notePath;
        this.chunkIndex = chunkIndex;
        this.source = source;
        this.text = text;
        this.imageRefs = imageRefs;
    }

    public String getNotePath() {
        return notePath;
    }

    public void setNotePath(String notePath) {
        this.notePath = notePath;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getImageRefs() {
        return imageRefs;
    }

    public void setImageRefs(List<String> imageRefs) {
        this.imageRefs = imageRefs;
    }
}
