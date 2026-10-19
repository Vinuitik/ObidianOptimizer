package com.obsidian.obsidian.vaults;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Rejects an API request that was composed for a different vault than the one now active:
 * the client sends {@code X-Vault-Id}; a mismatch means its screen shows the old vault
 * (e.g. another tab switched it), so a write would land in the wrong place. 409 tells the
 * client to reload. No header = allowed (old clients, curl, the embedder).
 */
@Component
public class VaultHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Vault-Id";

    private final ActiveVault activeVault;

    public VaultHeaderFilter(ActiveVault activeVault) {
        this.activeVault = activeVault;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // nginx/Vite strip the browser's /api/ prefix, so the app's own endpoints arrive as
        // /notes, /tracks, ...; /api/internal/** is service-to-service (no browser tab behind it).
        return request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            long active = activeVault.id();
            Long requested = parse(header.trim());
            if (requested == null || requested != active) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"error\":\"vault_mismatch\",\"activeVaultId\":" + active
                    + ",\"requestedVaultId\":" + (requested == null ? "null" : requested.toString()) + "}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static Long parse(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
