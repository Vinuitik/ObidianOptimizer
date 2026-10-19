package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.settings.SettingsRepository;
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

    // Settings-backed passphrase (editable in the UI); env `sync.passphrase` is the
    // fallback so headless installs keep working. Nullable so unit tests can keep
    // constructing the service bare and drive it via the `passphrase` field.
    private final SettingsRepository settingsRepo;

    // One AES key per vault (own passphrase); null entry = not configured.
    private final java.util.concurrent.ConcurrentHashMap<Long, SecretKey> keys = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public VaultEncryptionService(SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    VaultEncryptionService() {
        this.settingsRepo = null;
    }

    /** Derives (and caches) the key of one vault from its stored passphrase. The env
     *  `sync.passphrase` fallback applies to vault 1 only, so vaults never share a key by accident. */
    private synchronized SecretKey derive(long vaultId) {
        String effective = null;
        if (settingsRepo != null) {
            try { effective = settingsRepo.getSyncPassphrase(vaultId); } catch (Exception ignored) {}
        }
        if ((effective == null || effective.isBlank()) && vaultId == 1) effective = passphrase;

        if (effective == null || effective.isBlank()) {
            keys.remove(vaultId);
            log.warn("[VaultEncryptionService] sync passphrase not set for vault {} — encryption disabled", vaultId);
            return null;
        }
        try {
            KeySpec spec = new PBEKeySpec(effective.toCharArray(), PBKDF2_SALT, PBKDF2_ITERATIONS, KEY_BITS);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            SecretKey k = new SecretKeySpec(skf.generateSecret(spec).getEncoded(), "AES");
            keys.put(vaultId, k);
            log.info("[VaultEncryptionService] AES-256-GCM key derived for vault {}", vaultId);
            return k;
        } catch (Exception e) {
            keys.remove(vaultId);
            log.error("[VaultEncryptionService] key derivation failed: {}", e.getMessage());
            return null;
        }
    }

    private SecretKey keyFor(long vaultId) {
        SecretKey k = keys.get(vaultId);
        return k != null ? k : derive(vaultId);
    }

    /** Re-derive the key after the passphrase setting changed. */
    public void reload(long vaultId) {
        keys.remove(vaultId);
        derive(vaultId);
    }

    public boolean isConfigured(long vaultId) {
        return keyFor(vaultId) != null;
    }

    /**
     * Compress (gzip) then encrypt (AES-256-GCM).
     * Output format: [12B IV][GCM ciphertext + 16B auth tag]
     */
    public byte[] encrypt(long vaultId, byte[] plaintext) throws Exception {
        byte[] compressed = gzip(plaintext);

        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, requireKey(vaultId), new GCMParameterSpec(GCM_TAG_BITS, iv));
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
    public byte[] decrypt(long vaultId, byte[] encryptedBytes) throws Exception {
        byte[] iv         = Arrays.copyOfRange(encryptedBytes, 0,        IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encryptedBytes, IV_BYTES, encryptedBytes.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, requireKey(vaultId), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] compressed = cipher.doFinal(ciphertext);

        return ungzip(compressed);
    }

    private SecretKey requireKey(long vaultId) {
        SecretKey k = keyFor(vaultId);
        if (k == null) throw new IllegalStateException("sync passphrase not set for vault " + vaultId);
        return k;
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
