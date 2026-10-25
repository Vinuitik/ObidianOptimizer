package com.obsidian.obsidian.sync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultPathPropsTest {

    private static Map<String, String> write(String p) throws IOException {
        Map<String, String> m = new HashMap<>();
        VaultPathProps.write(m, p);
        return m;
    }

    private static void assertAllFit(Map<String, String> m) {
        m.forEach((k, v) -> assertThat(k.getBytes(StandardCharsets.UTF_8).length
            + v.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(124));
    }

    @Test
    void boundaryExactFitStaysLegacy() throws IOException {
        String p = "a".repeat(114);                       // 10 + 114 = 124
        Map<String, String> m = write(p);
        assertThat(m).containsOnlyKeys("vault_path");
        assertThat(VaultPathProps.read(m)).isEqualTo(p);
    }

    @Test
    void oneOverBoundaryIsChunked() throws IOException {
        String p = "a".repeat(115);
        Map<String, String> m = write(p);
        assertThat(m).doesNotContainKey("vault_path")
            .containsKeys("vault_path_n", "vault_path_sha1", "vault_path_0");
        assertAllFit(m);
        assertThat(VaultPathProps.read(m)).isEqualTo(p);
        assertThat(m.get("vault_path_sha1")).isEqualTo(VaultPathProps.sha1Hex(p)).hasSize(40);
    }

    @Test
    void multibyteNeverSplitMidCodepoint() throws IOException {
        for (String unit : new String[] {"—", "Привет", "日本", "😀"}) {
            String p = "notes/" + unit.repeat(120) + ".md";
            Map<String, String> m = write(p);
            assertAllFit(m);
            assertThat(VaultPathProps.read(m)).isEqualTo(p);
            for (int i = 0; i < Integer.parseInt(m.get("vault_path_n")); i++) {
                assertThat(m.get("vault_path_" + i)).doesNotContain("�");
            }
        }
    }

    @Test
    void legacyReadAndMissing() {
        assertThat(VaultPathProps.read(Map.of("vault_path", "x/y.md"))).isEqualTo("x/y.md");
        assertThat(VaultPathProps.read(Map.of())).isNull();
        assertThat(VaultPathProps.read(null)).isNull();
        assertThat(VaultPathProps.read(Map.of("vault_path_n", "2", "vault_path_0", "a"))).isNull();
    }

    @Test
    void tooLongRejectedAndLimitNotRetryable() {
        assertThatThrownBy(() -> write("a".repeat(2001))).isInstanceOf(IOException.class)
            .hasMessageContaining("too long");
        assertThat(DriveService.isTransient(403, "propertyLengthLimitExceeded")).isFalse();
    }

    @Test
    void maxPathStaysUnderThirtyProps() throws IOException {
        assertThat(write("a".repeat(2000)).size() + 3).isLessThanOrEqualTo(30);
    }
}
