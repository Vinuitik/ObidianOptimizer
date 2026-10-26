package com.obsidian.obsidian.importer;

/** An upload that must be refused outright (zip-slip, over limits). {@code status} = HTTP code. */
public class ImportRejectedException extends RuntimeException {
    private final int status;
    public ImportRejectedException(String message) { this(message, 400); }
    public ImportRejectedException(String message, int status) { super(message); this.status = status; }
    public int status() { return status; }
}
