package com.obsidian.obsidian.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimitTest {

    private static class MutableClock extends Clock {
        long now = 1_000_000L;
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    @Test
    void locksAfterFiveFailuresAndUnlocksAfterFifteenMinutes() {
        MutableClock c = new MutableClock();
        LoginAttemptService s = new LoginAttemptService(c);
        for (int i = 0; i < 4; i++) s.recordFailure("1.1.1.1");
        assertThat(s.retryAfterSeconds("1.1.1.1")).isZero();
        s.recordFailure("1.1.1.1");
        assertThat(s.retryAfterSeconds("1.1.1.1")).isEqualTo(900);
        assertThat(s.retryAfterSeconds("2.2.2.2")).isZero();
        c.now += LoginAttemptService.LOCK_MS + 1;
        assertThat(s.retryAfterSeconds("1.1.1.1")).isZero();
    }

    @Test
    void successResetsCounter() {
        LoginAttemptService s = new LoginAttemptService(new MutableClock());
        for (int i = 0; i < 4; i++) s.recordFailure("a");
        s.recordSuccess("a");
        s.recordFailure("a");
        assertThat(s.retryAfterSeconds("a")).isZero();
    }

    private MockHttpServletResponse post(LoginRateLimitFilter f, String cfIp, int downstreamStatus) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setServletPath("/login");
        req.setRemoteAddr("10.0.0.1");
        if (cfIp != null) req.addHeader("CF-Connecting-IP", cfIp);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                ((MockHttpServletResponse) rs).setStatus(downstreamStatus);
            }
        });
        return res;
    }

    @Test
    void filterReturns429AfterFiveBadLogins_perCfIp() throws Exception {
        LoginRateLimitFilter f = new LoginRateLimitFilter(new LoginAttemptService(new MutableClock()));
        for (int i = 0; i < 5; i++) assertThat(post(f, "9.9.9.9", 401).getStatus()).isEqualTo(401);
        MockHttpServletResponse blocked = post(f, "9.9.9.9", 200);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("900");
        // a different client behind the same proxy remote addr is unaffected
        assertThat(post(f, "8.8.8.8", 200).getStatus()).isEqualTo(200);
    }

    @Test
    void fallsBackToRemoteAddrWithoutCfHeader() throws Exception {
        LoginRateLimitFilter f = new LoginRateLimitFilter(new LoginAttemptService(new MutableClock()));
        for (int i = 0; i < 5; i++) post(f, null, 401);
        assertThat(post(f, null, 200).getStatus()).isEqualTo(429);
    }
}
