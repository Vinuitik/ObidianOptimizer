package com.obsidian.obsidian.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NotionNormalizerTest {
    static final String ID_A = "1a2b3c4d5e6f1a2b3c4d5e6f1a2b3c4d";
    static final String ID_B = "ffffffffffffffffffffffffffffffff";
    static final String ID_C = "00000000000000000000000000000001";

    static final ZipImporter.Limits LIMITS = new ZipImporter.Limits(10L << 20, 100, 50L << 20);

    @TempDir Path tmp;

    private Path zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        Path p = Files.createTempFile(tmp, "t", ".zip");
        Files.write(p, bos.toByteArray());
        return p;
    }

    @Test
    void stripsIdFromFilesAndFolders() {
        assertEquals("Page.md", NotionNormalizer.stripId("Page " + ID_A + ".md"));
        assertEquals("Folder", NotionNormalizer.stripId("Folder " + ID_A));
        assertEquals("No id.md", NotionNormalizer.stripId("No id.md"));
        assertEquals("Short 1a2b.md", NotionNormalizer.stripId("Short 1a2b.md"));
    }

    @Test
    void detectsNotionOnlyWithIdSuffixedMarkdown() {
        assertTrue(NotionNormalizer.looksLikeNotion(List.of("A " + ID_A + ".md")));
        assertFalse(NotionNormalizer.looksLikeNotion(List.of("notes/plain.md", "img.png")));
    }

    @Test
    void renamesDedupeCollisionsAndConvertCsv() {
        Map<String, String> r = NotionNormalizer.planRenames(List.of(
            "Dup " + ID_A + ".md", "Dup " + ID_B + ".md",
            "Dir " + ID_A + "/Child " + ID_C + ".md",
            "Dir " + ID_B + "/Other.md",
            "DB " + ID_A + ".csv"));
        assertEquals("Dup.md", r.get("Dup " + ID_A + ".md"));
        assertEquals("Dup (2).md", r.get("Dup " + ID_B + ".md"));
        assertEquals("Dir/Child.md", r.get("Dir " + ID_A + "/Child " + ID_C + ".md"));
        assertEquals("Dir (2)/Other.md", r.get("Dir " + ID_B + "/Other.md"));
        assertEquals("DB.md", r.get("DB " + ID_A + ".csv"));
    }

    @Test
    void rewritesPageLinksToWikilinksAndAttachmentsToRenamedPaths() {
        String page = "Some Page " + ID_A + ".md";
        String sub = "Some Page " + ID_A + "/Child " + ID_B + ".md";
        String img = "Some Page " + ID_A + "/pic " + ID_C + ".png";
        Map<String, String> r = NotionNormalizer.planRenames(List.of(page, sub, img));
        String md = "See [Some Page](Some%20Page%20" + ID_A + ".md) and [the kid](Some%20Page%20" + ID_A
            + "/Child%20" + ID_B + ".md).\n![shot](Some%20Page%20" + ID_A + "/pic%20" + ID_C + ".png)\n"
            + "[web](https://example.com/x.md) [gone](Missing%20" + ID_A + ".md)";
        String out = NotionNormalizer.rewriteLinks(md, page, r);
        assertTrue(out.contains("[[Some Page]]"), out);
        assertTrue(out.contains("[[Child|the kid]]"), out);
        assertTrue(out.contains("![shot](Some%20Page/pic.png)"), out);
        assertTrue(out.contains("[web](https://example.com/x.md)"), out);
        assertTrue(out.contains("[gone](Missing%20" + ID_A + ".md)"), out);

        // Link from inside the subfolder back up to the parent page.
        String back = NotionNormalizer.rewriteLinks("[up](../Some%20Page%20" + ID_A + ".md)", sub, r);
        assertEquals("[[Some Page|up]]", back);
    }

    @Test
    void csvBecomesMarkdownTable() {
        String md = NotionNormalizer.csvToMarkdown("\uFEFFName,Notes\r\nA,\"hello, \"\"world\"\"\"\nB,\"line1\nline2|x\"\n");
        assertEquals("| Name | Notes |\n| --- | --- |\n| A | hello, \"world\" |\n| B | line1<br>line2\\|x |\n", md);
    }

    @Test
    void sanitizeRejectsZipSlipAndAbsolutePaths() {
        assertThrows(ImportRejectedException.class, () -> ImportPaths.sanitize("../evil.md"));
        assertThrows(ImportRejectedException.class, () -> ImportPaths.sanitize("a/../../evil.md"));
        assertThrows(ImportRejectedException.class, () -> ImportPaths.sanitize("a\\..\\evil.md"));
        assertThrows(ImportRejectedException.class, () -> ImportPaths.sanitize("/etc/passwd"));
        assertThrows(ImportRejectedException.class, () -> ImportPaths.sanitize("C:/x.md"));
        assertEquals("a/b.md", ImportPaths.sanitize("./a//b.md"));
    }

    @Test
    void zipWithSlipEntryIsRejectedAndWritesNothing() throws IOException {
        Path z = zip(Map.of("ok.md", "hi", "../../escape.md", "pwn"));
        Path root = tmp.resolve("Imported");
        assertThrows(ImportRejectedException.class, () -> ZipImporter.run(z, root, "2026-10-26", LIMITS));
        assertFalse(Files.exists(root.resolve("obsidian-2026-10-26")));
        assertFalse(Files.exists(tmp.resolve("escape.md")));
    }

    @Test
    void notionZipEndToEnd() throws IOException {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("Export/Home " + ID_A + ".md", "# Home\n[Sub](Home%20" + ID_A + "/Sub%20" + ID_B + ".md)\n![p](Home%20" + ID_A + "/p%20" + ID_C + ".png)");
        e.put("Export/Home " + ID_A + "/Sub " + ID_B + ".md", "# Sub");
        e.put("Export/Home " + ID_A + "/p " + ID_C + ".png", "PNGDATA");
        e.put("Export/DB " + ID_A + ".csv", "a,b\n1,2\n");
        e.put("Export/DB " + ID_A + "_all.csv", "a,b\n1,2\n");
        e.put("__MACOSX/junk", "x");
        e.put("inner.zip", "x");
        ZipImporter.Result r = ZipImporter.run(zip(e), tmp.resolve("Imported"), "2026-10-26", LIMITS);
        assertEquals("notion", r.source());
        assertEquals(3, r.notes());
        assertEquals(1, r.attachments());
        assertEquals(3, r.skipped().size());
        Path d = tmp.resolve("Imported").resolve("notion-2026-10-26").resolve("Export");
        assertTrue(Files.readString(d.resolve("Home.md")).contains("[[Sub]]"));
        assertTrue(Files.readString(d.resolve("Home.md")).contains("![p](Home/p.png)"));
        assertTrue(Files.exists(d.resolve("Home/p.png")));
        assertTrue(Files.readString(d.resolve("DB.md")).contains("| a | b |"));
    }

    @Test
    void plainMarkdownCopiedAsIsAndNameCollisionGetsSuffix() throws IOException {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("vault/A.md", "[[B]] stays\n[x](B%20" + ID_A + ".md)");
        e.put("vault/img/a.png", "bin");
        Path root = tmp.resolve("Imported");
        ZipImporter.Result r1 = ZipImporter.run(zip(e), root, "2026-10-26", LIMITS);
        ZipImporter.Result r2 = ZipImporter.run(zip(e), root, "2026-10-26", LIMITS);
        assertEquals("obsidian", r1.source());
        assertEquals("obsidian-2026-10-26", r1.folder());
        assertEquals("obsidian-2026-10-26 (2)", r2.folder());
        assertEquals("[[B]] stays\n[x](B%20" + ID_A + ".md)", Files.readString(root.resolve(r1.folder()).resolve("vault/A.md")));
        assertTrue(Files.exists(root.resolve(r1.folder()).resolve("vault/img/a.png")));
    }

    @Test
    void entryAndSizeLimitsRejected() throws IOException {
        Map<String, String> e = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) e.put("n" + i + ".md", "x");
        Path z = zip(e);
        assertThrows(ImportRejectedException.class,
            () -> ZipImporter.run(z, tmp.resolve("I1"), "d", new ZipImporter.Limits(10L << 20, 3, 1 << 20)));
        assertThrows(ImportRejectedException.class,
            () -> ZipImporter.run(z, tmp.resolve("I2"), "d", new ZipImporter.Limits(10, 100, 1 << 20)));
        assertThrows(ImportRejectedException.class,
            () -> ZipImporter.run(z, tmp.resolve("I3"), "d", new ZipImporter.Limits(10L << 20, 100, 2)));
    }

    @Test
    void docxTextExtraction() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("word/document.xml"));
            z.write("<w:document><w:body><w:p><w:r><w:t>Hello &amp; </w:t></w:r><w:r><w:t xml:space=\"preserve\">world</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r></w:p></w:body></w:document>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertEquals("Hello & world\n\nSecond", DocxText.extract(bos.toByteArray()));
    }
}
