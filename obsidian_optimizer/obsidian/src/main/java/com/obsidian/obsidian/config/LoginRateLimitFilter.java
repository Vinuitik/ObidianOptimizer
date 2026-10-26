package com.obsidian.obsidian.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Not a bean (keeps @WebMvcTest slices wiring-free). Guards POST /login: 429 while the client IP is locked; counts 401s as failures. */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginAttemptService attempts;

    public LoginRateLimitFilter(LoginAttemptService attempts) { this.attempts = attempts; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !("POST".equals(req.getMethod()) && "/login".equals(req.getServletPath()));
    }

    static String clientIp(HttpServletRequest req) {
        String cf = req.getHeader("CF-Connecting-IP");
        return cf != null && !cf.isBlank() ? cf.trim() : req.getRemoteAddr();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(req);
        long wait = attempts.retryAfterSeconds(ip);
        if (wait > 0) {
            res.setHeader("Retry-After", Long.toString(wait));
            res.setStatus(429);
            return;
        }
        chain.doFilter(req, res);
        int s = res.getStatus();
        if (s == HttpServletResponse.SC_UNAUTHORIZED) attempts.recordFailure(ip);
        else if (s == HttpServletResponse.SC_OK) attempts.recordSuccess(ip);
    }
}
