package com.obsidian.obsidian.sync;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class VaultEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(VaultEncryptionService.class);

    // Fixed salt — all devices with the same passphrase derive the same AES key.
    // Acceptable for a personal sync tool; change the passphrase to rotate.
    private static final byte[] PBKDF2_SALT = "ObsidianSyncSalt".getBytes(StandardCharsets.UTF_8);
    private static final int    PBKDF2_ITERATIONS = 310_000;
    private static final int    KEY_BITS          = 256;
    private static final int    IV_BYTES          = 12;  // GCM nonce
    private static final int    GCM_TAG_BITS      = 128;

    @Value("${sync.passphrase:}")
    private String passphrase;

    private SecretKey key;

    @PostConstruct
    public void init() {
        if (passphrase == null || passphrase.isBlank()) {
            log.warn("[VaultEncryptionService] sync.passphrase not set — encryption disabled");
            return;
        }
        try {
            KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), PBKDF2_SALT, PBKDF2_ITERATIONS, KEY_BITS);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            key = new SecretKeySpec(skf.generateSecret(spec).getEncoded(), "AES");
            log.info("[VaultEncryptionService] AES-256-GCM key derived from passphrase");
        } catch (Exception e) {
            log.error("[VaultEncryptionService] key derivation failed: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    /**
     * Compress (gzip) then encrypt (AES-256-GCM).
     * Output format: [12B IV][GCM ciphertext + 16B auth tag]
     */
    public byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] compressed = gzip(plaintext);

        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(compressed);

        byte[] out = new byte[IV_BYTES + ciphertext.length];
        System.arraycopy(iv,         0, out, 0,        IV_BYTES);
        System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
        return out;
    }

    /**
     * Decrypt then decompress.
     * Expects the format produced by {@link #encrypt}.
     */
    public byte[] decrypt(byte[] encryptedBytes) throws Exception {
        byte[] iv         = Arrays.copyOfRange(encryptedBytes, 0,        IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encryptedBytes, IV_BYTES, encryptedBytes.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] compressed = cipher.doFinal(ciphertext);

        return ungzip(compressed);
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] ungzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            gz.transferTo(bos);
        }
        return bos.toByteArray();
    }
}
