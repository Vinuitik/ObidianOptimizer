package com.obsidian.obsidian.ml;

public class SearchResult {
    private String notePath;
    private String snippet;
    private double score;

    public SearchResult() {
    }

    public SearchResult(String notePath, String snippet, double score) {
        this.notePath = notePath;
        this.snippet = snippet;
        this.score = score;
    }

    public String getNotePath() {
        return notePath;
    }

    public void setNotePath(String notePath) {
        this.notePath = notePath;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
