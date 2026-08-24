package com.obsidian.obsidian.ml;

/** One note's representative embedding vector — see NoteChunkRepository.findAveragedTextVectors. */
public record NoteVector(String path, float[] vector) {
}
