package com.obsidian.obsidian.vaults;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VaultHeaderFilterTest {

    private final ActiveVault active = ActiveVault.forTest(2L, Path.of("/vaults/2"));
    private final VaultHeaderFilter filter = new VaultHeaderFilter(active);

    private MockHttpServletResponse run(String uri, String header) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        if (header != null) req.addHeader(VaultHeaderFilter.HEADER, header);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        // chain.getRequest() is set only when the filter let the request through
        res.setHeader("X-Passed", chain.getRequest() != null ? "yes" : "no");
        return res;
    }

    @Test
    void noHeader_isAllowed() throws Exception {
        assertThat(run("/notes", null).getHeader("X-Passed")).isEqualTo("yes");
    }

    @Test
    void matchingHeader_isAllowed() throws Exception {
        assertThat(run("/notes", "2").getHeader("X-Passed")).isEqualTo("yes");
    }

    @Test
    void mismatchedHeader_is409Json() throws Exception {
        MockHttpServletResponse res = run("/notes", "1");
        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentType()).startsWith("application/json");
        assertThat(res.getContentAsString()).contains("vault_mismatch").contains("\"activeVaultId\":2");
        assertThat(res.getHeader("X-Passed")).isEqualTo("no");
    }

    @Test
    void garbageHeader_is409() throws Exception {
        assertThat(run("/notes", "abc").getStatus()).isEqualTo(409);
    }

    @Test
    void internalServicePaths_areNotFiltered() throws Exception {
        assertThat(run("/api/internal/notes", "1").getHeader("X-Passed")).isEqualTo("yes");
    }
}
