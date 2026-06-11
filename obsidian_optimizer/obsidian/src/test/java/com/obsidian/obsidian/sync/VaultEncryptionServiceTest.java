package com.obsidian.obsidian.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultEncryptionServiceTest {

    private VaultEncryptionService service;

    @BeforeEach
    void setUp() {
        service = new VaultEncryptionService();
        ReflectionTestUtils.setField(service, "passphrase", "correct horse battery staple");
        service.init();
    }

    private static VaultEncryptionService withPassphrase(String passphrase) {
        VaultEncryptionService s = new VaultEncryptionService();
        ReflectionTestUtils.setField(s, "passphrase", passphrase);
        s.init();
        return s;
    }

    @Test
    void roundTrip_recoversExactPlaintext() throws Exception {
        byte[] plaintext = "# Note\n\nSome content with unicode: ééé 日本語\n".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = service.encrypt(plaintext);
        byte[] decrypted = service.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_binaryContent() throws Exception {
        byte[] plaintext = new byte[4096];
        new java.util.Random(7).nextBytes(plaintext);
        assertThat(service.decrypt(service.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_emptyContent() throws Exception {
        byte[] plaintext = new byte[0];
        assertThat(service.decrypt(service.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachTime_freshIvPerFile() throws Exception {
        byte[] plaintext = "same content".getBytes(StandardCharsets.UTF_8);
        byte[] first  = service.encrypt(plaintext);
        byte[] second = service.encrypt(plaintext);
        assertThat(first).isNotEqualTo(second);
        // IVs (first 12 bytes) must differ — IV reuse breaks GCM completely
        assertThat(Arrays.copyOfRange(first, 0, 12))
            .isNotEqualTo(Arrays.copyOfRange(second, 0, 12));
    }

    @Test
    void decrypt_tamperedCiphertext_failsAuthentication() throws Exception {
        byte[] encrypted = service.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        encrypted[encrypted.length - 1] ^= 0x01; // flip one bit in the auth tag region
        assertThatThrownBy(() -> service.decrypt(encrypted))
            .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void decrypt_withWrongPassphrase_failsAuthentication() throws Exception {
        byte[] encrypted = service.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        VaultEncryptionService other = withPassphrase("wrong passphrase");
        assertThatThrownBy(() -> other.decrypt(encrypted))
            .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void samePassphraseOnAnotherDevice_decrypts_fixedSaltCompatibility() throws Exception {
        byte[] encrypted = service.encrypt("multi-device".getBytes(StandardCharsets.UTF_8));
        VaultEncryptionService otherDevice = withPassphrase("correct horse battery staple");
        assertThat(otherDevice.decrypt(encrypted))
            .isEqualTo("multi-device".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void isConfigured_falseWhenPassphraseBlank() {
        VaultEncryptionService unconfigured = new VaultEncryptionService();
        ReflectionTestUtils.setField(unconfigured, "passphrase", "");
        unconfigured.init();
        assertThat(unconfigured.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_trueAfterInit() {
        assertThat(service.isConfigured()).isTrue();
    }
}
