package com.obsidian.obsidian.importer;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Notion "Markdown & CSV" export normaliser: id-suffix stripping, collision-safe renames,
 * internal link rewrite, CSV database → markdown table. No I/O.
 */
public final class NotionNormalizer {
    private NotionNormalizer() {}

    /** " 1a2b…(32 hex)" right before the extension (or at the end, for folders). */
    private static final Pattern ID_SUFFIX = Pattern.compile(" [0-9a-fA-F]{32}(?=\\.[^./]+$|$)");
    private static final Pattern LINK = Pattern.compile("(!?)\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");

    public static boolean looksLikeNotion(Collection<String> paths) {
        for (String p : paths) {
            if (p.toLowerCase().endsWith(".md") && ID_SUFFIX.matcher(lastSegment(p)).find()) return true;
        }
        return false;
    }

    /** "Page 1a2b…f.md" → "Page.md"; folder "Dir 1a2b…f" → "Dir". */
    public static String stripId(String segment) {
        return ID_SUFFIX.matcher(segment).replaceFirst("");
    }

    /**
     * old path → new path for every entry. Each segment loses its id suffix, .csv becomes .md,
     * and clashes (case-insensitive) get " (2)", " (3)"… Deterministic (input sorted).
     */
    public static Map<String, String> planRenames(Collection<String> paths) {
        List<String> sorted = new ArrayList<>(paths);
        Collections.sort(sorted);
        Map<String, String> dirMap = new HashMap<>();      // old dir path → new dir path
        Set<String> claimedDirs = new HashSet<>();         // lower-cased new dir paths
        Set<String> claimedFiles = new HashSet<>();
        Map<String, String> out = new LinkedHashMap<>();
        for (String old : sorted) {
            String[] segs = old.split("/");
            StringBuilder oldPrefix = new StringBuilder();
            String newParent = "";
            for (int i = 0; i < segs.length - 1; i++) {
                if (i > 0) oldPrefix.append('/');
                oldPrefix.append(segs[i]);
                String key = oldPrefix.toString();
                String mapped = dirMap.get(key);
                if (mapped == null) {
                    mapped = unique(join(newParent, stripId(segs[i])), claimedDirs, false);
                    dirMap.put(key, mapped);
                }
                newParent = mapped;
            }
            String file = stripId(segs[segs.length - 1]);
            if (file.toLowerCase().endsWith(".csv")) file = file.substring(0, file.length() - 4) + ".md";
            out.put(old, unique(join(newParent, file), claimedFiles, true));
        }
        return out;
    }

    private static String unique(String candidate, Set<String> claimed, boolean file) {
        String c = candidate;
        int n = 2;
        while (!claimed.add(c.toLowerCase())) {
            c = file ? withSuffix(candidate, " (" + n + ")") : candidate + " (" + n + ")";
            n++;
        }
        return c;
    }

    private static String withSuffix(String path, String suffix) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash ? path.substring(0, dot) + suffix + path.substring(dot) : path + suffix;
    }

    /**
     * Rewrite markdown links that point at other exported files. Notes → {@code [[Name]]}
     * (or {@code [[Name|Text]]}); attachments → relative path to the renamed file.
     * External / unknown targets are left untouched.
     */
    public static String rewriteLinks(String md, String oldPath, Map<String, String> renames) {
        String newPath = renames.getOrDefault(oldPath, oldPath);
        String newDir = parent(newPath);
        String oldDir = parent(oldPath);
        Matcher m = LINK.matcher(md);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean embed = !m.group(1).isEmpty();
            String text = m.group(2);
            String url = m.group(3);
            String replacement = m.group(0);
            if (!SCHEME.matcher(url).matches() && !url.startsWith("#") && !url.startsWith("/")) {
                String target = resolve(oldDir, decode(url.replaceAll("#.*$", "")));
                String renamed = target == null ? null : renames.get(target);
                if (renamed != null) {
                    if (renamed.toLowerCase().endsWith(".md")) {
                        String stem = stem(renamed);
                        String inner = (embed || text.isBlank() || text.equals(stem)) ? stem : stem + "|" + text;
                        replacement = (embed ? "!" : "") + "[[" + inner + "]]";
                    } else {
                        replacement = m.group(1) + "[" + text + "](" + encode(relative(newDir, renamed)) + ")";
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Notion database CSV → one GitHub-style markdown table (cells' newlines become {@code <br>}). */
    public static String csvToMarkdown(String csv) {
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) return "";
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            sb.append('|');
            for (int c = 0; c < cols; c++) sb.append(' ').append(cell(c < row.size() ? row.get(c) : "")).append(" |");
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                for (int c = 0; c < cols; c++) sb.append(" --- |");
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n').replace("|", "\\|").replace("\n", "<br>").trim();
    }

    static List<List<String>> parseCsv(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else cur.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { row.add(cur.toString()); cur.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(cur.toString()); cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cur.append(ch);
        }
        if (cur.length() > 0 || !row.isEmpty()) {
            row.add(cur.toString());
            if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
        }
        return rows;
    }

    // ── path helpers (POSIX, no I/O) ──────────────────────────────────────────

    private static String lastSegment(String p) { return p.substring(p.lastIndexOf('/') + 1); }
    private static String parent(String p) { int i = p.lastIndexOf('/'); return i < 0 ? "" : p.substring(0, i); }
    private static String join(String a, String b) { return a.isEmpty() ? b : a + "/" + b; }
    private static String stem(String p) {
        String n = lastSegment(p);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String decode(String url) {
        try { return URLDecoder.decode(url.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return url; }
    }

    private static String encode(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/", -1)) {
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    /** Resolve a relative link against a directory; null if it escapes the archive root. */
    static String resolve(String dir, String rel) {
        Deque<String> stack = new ArrayDeque<>();
        if (!dir.isEmpty()) for (String s : dir.split("/")) stack.addLast(s);
        for (String s : rel.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) { if (stack.isEmpty()) return null; stack.removeLast(); }
            else stack.addLast(s);
        }
        return String.join("/", stack);
    }

    static String relative(String fromDir, String to) {
        List<String> a = fromDir.isEmpty() ? List.of() : Arrays.asList(fromDir.split("/"));
        List<String> b = Arrays.asList(to.split("/"));
        int i = 0;
        while (i < a.size() && i < b.size() - 1 && a.get(i).equals(b.get(i))) i++;
        List<String> parts = new ArrayList<>();
        for (int k = i; k < a.size(); k++) parts.add("..");
        parts.addAll(b.subList(i, b.size()));
        return String.join("/", parts);
    }
}
