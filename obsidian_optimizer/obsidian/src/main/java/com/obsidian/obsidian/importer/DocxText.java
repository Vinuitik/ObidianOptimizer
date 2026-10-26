package com.obsidian.obsidian.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal .docx → plain text (paragraphs only; no tables/images/styles). No library dependency. */
public final class DocxText {
    private DocxText() {}

    private static final long MAX_XML = 50L * 1024 * 1024;

    public static String extract(byte[] docx) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (e.getName().equals("word/document.xml")) return paragraphs(readCapped(zin));
            }
        }
        throw new IOException("Not a valid .docx (no word/document.xml)");
    }

    private static String readCapped(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_XML) throw new IOException("document.xml too large");
            bos.write(buf, 0, n);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    private static final Pattern PARA = Pattern.compile("<w:p[ >].*?</w:p>", Pattern.DOTALL);
    private static final Pattern TEXT = Pattern.compile("<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>", Pattern.DOTALL);

    static String paragraphs(String xml) {
        StringBuilder out = new StringBuilder();
        Matcher p = PARA.matcher(xml);
        while (p.find()) {
            StringBuilder line = new StringBuilder();
            Matcher t = TEXT.matcher(p.group());
            while (t.find()) {
                if (t.group(1) != null) line.append(unescape(t.group(1)));
                else line.append(t.group().equals("<w:tab/>") ? "\t" : " ");
            }
            out.append(line).append("\n\n");
        }
        return out.toString().trim();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
