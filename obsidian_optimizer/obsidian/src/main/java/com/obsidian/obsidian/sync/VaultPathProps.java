package com.obsidian.obsidian.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Encodes a vault-relative path into Drive appProperties. Drive caps each appProperty at
 * 124 bytes (UTF-8 key + value), so long paths are split into {@code vault_path_0..n-1}
 * chunks (+ {@code vault_path_n} count and {@code vault_path_sha1} for exact lookups).
 * Short paths keep the legacy single {@code vault_path} key.
 */
final class VaultPathProps {
    static final int LIMIT = 124;
    static final int MAX_PATH_BYTES = 2000;

    private VaultPathProps() {}

    static void write(Map<String, String> props, String path) throws IOException {
        int bytes = path.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PATH_BYTES) {
            throw new IOException("vault path too long for Drive appProperties ("
                + bytes + " bytes > " + MAX_PATH_BYTES + "): " + path);
        }
        if ("vault_path".length() + bytes <= LIMIT) {
            props.put("vault_path", path);
            return;
        }
        List<String> chunks = chunk(path);
        for (int i = 0; i < chunks.size(); i++) props.put("vault_path_" + i, chunks.get(i));
        props.put("vault_path_n", String.valueOf(chunks.size()));
        props.put("vault_path_sha1", sha1Hex(path));
    }

    /** Split on code-point boundaries so each chunk with its key fits in LIMIT bytes. */
    static List<String> chunk(String path) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curBytes = 0;
        int budget = LIMIT - ("vault_path_" + 0).length();
        for (int i = 0; i < path.length(); ) {
            int cp = path.codePointAt(i);
            int b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (curBytes + b > budget) {
                out.add(cur.toString());
                cur.setLength(0);
                curBytes = 0;
                budget = LIMIT - ("vault_path_" + out.size()).length();
            }
            cur.appendCodePoint(cp);
            curBytes += b;
            i += Character.charCount(cp);
        }
        out.add(cur.toString());
        return out;
    }

    /** Returns the vault path from legacy or chunked props, or null if absent/incomplete. */
    static String read(Map<String, String> props) {
        if (props == null) return null;
        String single = props.get("vault_path");
        if (single != null) return single;
        String n = props.get("vault_path_n");
        if (n == null) return null;
        int count;
        try { count = Integer.parseInt(n); } catch (NumberFormatException e) { return null; }
        if (count <= 0 || count > 30) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String c = props.get("vault_path_" + i);
            if (c == null) return null;
            sb.append(c);
        }
        return sb.toString();
    }

    static String sha1Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
