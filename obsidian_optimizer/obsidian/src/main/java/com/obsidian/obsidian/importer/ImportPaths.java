package com.obsidian.obsidian.importer;

import java.util.ArrayList;
import java.util.List;

/** Zip-entry path hygiene. Pure; the zip-slip gate for every imported path. */
public final class ImportPaths {
    private ImportPaths() {}

    /**
     * Normalise an archive entry name to a safe vault-relative POSIX path.
     * Rejects (throws) absolute paths, drive letters, NULs and any ".." segment;
     * drops "." and empty segments.
     */
    public static String sanitize(String entryName) {
        if (entryName == null || entryName.isEmpty()) throw new ImportRejectedException("Empty entry name");
        String n = entryName.replace('\\', '/');
        if (n.indexOf('\0') >= 0) throw new ImportRejectedException("Unsafe entry name: " + printable(entryName));
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) {
            throw new ImportRejectedException("Unsafe entry (absolute path): " + printable(entryName));
        }
        List<String> out = new ArrayList<>();
        for (String seg : n.split("/")) {
            if (seg.equals("..")) throw new ImportRejectedException("Unsafe entry (path traversal): " + printable(entryName));
            if (seg.isEmpty() || seg.equals(".")) continue;
            out.add(seg);
        }
        if (out.isEmpty()) throw new ImportRejectedException("Empty entry name");
        return String.join("/", out);
    }

    /** Junk that should never be imported (macOS metadata, Obsidian config, dotfiles); null = keep. */
    public static String junkReason(String cleanPath) {
        for (String seg : cleanPath.split("/")) {
            if (seg.equals("__MACOSX")) return "macOS metadata";
            if (seg.equals(".obsidian")) return "Obsidian settings folder";
            if (seg.startsWith(".")) return "hidden file";
        }
        return null;
    }

    private static String printable(String s) { return s.length() > 80 ? s.substring(0, 80) + "…" : s; }
}
