package com.obsidian.obsidian.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards which Drive write failures the uploader retries. Getting this wrong either
 * strands files on a transient blip (too strict) or hammers Drive on a permanent error
 * like 404/auth/storage-quota (too loose).
 */
class DriveServiceTest {

    @Test
    void rateLimitAndServerErrorsAreTransient() {
        assertThat(DriveService.isTransient(429, "")).isTrue();
        assertThat(DriveService.isTransient(500, "")).isTrue();
        assertThat(DriveService.isTransient(503, "backendError")).isTrue();
        assertThat(DriveService.isTransient(403, "rateLimitExceeded")).isTrue();
        assertThat(DriveService.isTransient(403, "userRateLimitExceeded")).isTrue();
    }

    @Test
    void permanentErrorsAreNotTransient() {
        assertThat(DriveService.isTransient(404, "notFound")).isFalse();
        assertThat(DriveService.isTransient(401, "authError")).isFalse();
        assertThat(DriveService.isTransient(400, "badRequest")).isFalse();
        // 403 that is NOT rate-limiting (e.g. storageQuotaExceeded) must not spin
        assertThat(DriveService.isTransient(403, "storageQuotaExceeded")).isFalse();
        assertThat(DriveService.isTransient(403, null)).isFalse();
    }
}
