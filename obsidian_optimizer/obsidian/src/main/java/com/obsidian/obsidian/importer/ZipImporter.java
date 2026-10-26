package com.obsidian.obsidian.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts a zip (Notion export or plain markdown/Obsidian folder) into a fresh
 * {@code <importsRoot>/<source>-<date>/} directory. No Spring, no DB — the caller indexes afterwards.
 * Two phases: (1) validate every entry (zip-slip, limits) before writing anything, (2) write.
 */
public final class ZipImporter {
    private ZipImporter() {}

    public record Limits(long maxZipBytes, int maxEntries, long maxUncompressedBytes) {}
    public record Skip(String path, String reason) {}
    public record Result(String source, String folder, int notes, int attachments, List<Skip> skipped) {}

    /** Markdown entries above this are streamed verbatim (no link rewrite) to bound heap use. */
    static final long MAX_IN_MEMORY_MD = 10L * 1024 * 1024;

    public static Result run(Path zipFile, Path importsRoot, String date, Limits limits) throws IOException {
        if (Files.size(zipFile) > limits.maxZipBytes()) {
            throw new ImportRejectedException("Zip is larger than the " + (limits.maxZipBytes() >> 20) + " MB limit", 413);
        }
        try (ZipFile zf = new ZipFile(zipFile.toFile())) {
            if (zf.size() > limits.maxEntries()) {
                throw new ImportRejectedException("Zip has more than " + limits.maxEntries() + " entries", 413);
            }
            // Phase 1: validate everything up front.
            Map<String, ZipEntry> files = new LinkedHashMap<>();
            List<Skip> skipped = new ArrayList<>();
            long declared = 0;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String clean = ImportPaths.sanitize(e.getName());   // throws → whole archive rejected
                if (e.isDirectory()) continue;
                declared += Math.max(0, e.getSize());
                if (declared > limits.maxUncompressedBytes()) {
                    throw new ImportRejectedException("Zip expands to more than "
                        + (limits.maxUncompressedBytes() >> 20) + " MB", 413);
                }
                String junk = ImportPaths.junkReason(clean);
                if (junk != null) { skipped.add(new Skip(clean, junk)); continue; }
                if (clean.toLowerCase().endsWith(".zip")) {
                    skipped.add(new Skip(clean, "nested zip — unzip it once and upload the inner zip"));
                    continue;
                }
                files.put(clean, e);
            }

            boolean notion = NotionNormalizer.looksLikeNotion(files.keySet());
            if (notion) {
                for (Iterator<String> it = files.keySet().iterator(); it.hasNext(); ) {
                    String p = it.next();
                    if (p.toLowerCase().endsWith("_all.csv")) {
                        skipped.add(new Skip(p, "duplicate of the database table"));
                        it.remove();
                    }
                }
            }
            String source = notion ? "notion" : "obsidian";
            Map<String, String> renames = notion ? NotionNormalizer.planRenames(files.keySet()) : Map.of();

            Files.createDirectories(importsRoot);
            Path dest = uniqueDir(importsRoot, source + "-" + date);
            long[] budget = { limits.maxUncompressedBytes() };
            int notes = 0, attachments = 0;
            

            // Phase 2: write. Any failure removes the half-written folder.
            try {
            for (Map.Entry<String, ZipEntry> me : files.entrySet()) {
                String old = me.getKey();
                ZipEntry e = me.getValue();
                String rel = renames.getOrDefault(old, old);
                Path target = dest.resolve(rel).normalize();
                if (!target.startsWith(dest)) throw new ImportRejectedException("Unsafe entry: " + old);
                Files.createDirectories(target.getParent());
                String lower = old.toLowerCase();
                boolean isMd = lower.endsWith(".md");
                boolean isCsv = notion && lower.endsWith(".csv");
                if ((isMd || isCsv) && e.getSize() <= MAX_IN_MEMORY_MD) {
                    String text;
                    try (InputStream in = zf.getInputStream(e)) {
                        text = new String(readBounded(in, budget), StandardCharsets.UTF_8);
                    }
                    if (isCsv) {
                        String title = stemOf(rel);
                        text = "# " + title + "\n\n" + NotionNormalizer.csvToMarkdown(text);
                    } else if (notion) {
                        text = NotionNormalizer.rewriteLinks(text, old, renames);
                    }
                    Files.writeString(target, text);
                    notes++;
                } else {
                    try (InputStream in = zf.getInputStream(e); OutputStream out = Files.newOutputStream(target)) {
                        copyBounded(in, out, budget);
                    }
                    if (isMd) notes++; else attachments++;
                }
            }
            } catch (IOException | RuntimeException ex) {
                deleteTree(dest);
                throw ex;
            }
            return new Result(source, dest.getFileName().toString(), notes, attachments, skipped);
        }
    }

    static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { }
    }

    private static String stemOf(String rel) {
        String n = rel.substring(rel.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** {@code root/<base>}, or {@code root/<base> (2)}… if it already exists. */
    static Path uniqueDir(Path root, String base) throws IOException {
        Path p = root.resolve(base);
        for (int n = 2; Files.exists(p); n++) p = root.resolve(base + " (" + n + ")");
        Files.createDirectories(p);
        return p;
    }

    private static byte[] readBounded(InputStream in, long[] budget) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        copyBounded(in, bos, budget);
        return bos.toByteArray();
    }

    /** Copy while charging the shared uncompressed budget — defeats zips whose headers lie about size. */
    private static void copyBounded(InputStream in, OutputStream out, long[] budget) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            budget[0] -= n;
            if (budget[0] < 0) throw new ImportRejectedException("Zip expands beyond the configured size limit", 413);
            out.write(buf, 0, n);
        }
    }
}
