package com.obsidian.obsidian.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The single home for content hashing. Every pipeline that diffs "has this
 * changed?" — Drive sync (content_hash), card generation (body_hash), chunk
 * embedding, image captioning — hashes through here, so there is exactly one
 * hash function in the codebase.
 *
 * This is the MECHANISM only. What each domain hashes (whole file vs
 * frontmatter-stripped body vs a single chunk) and when it decides to act on a
 * difference stays in that domain — those keys are deliberately different (e.g.
 * body_hash exists so frontmatter rewrites don't re-trigger card generation),
 * and folding them together here would just relocate that coupling.
 */
public final class ContentHashing {

    private ContentHashing() {}

    public static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
