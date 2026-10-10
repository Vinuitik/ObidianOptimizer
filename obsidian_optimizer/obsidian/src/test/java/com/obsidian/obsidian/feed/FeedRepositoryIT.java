package com.obsidian.obsidian.feed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "ingest.enabled=false", "subscriptions.poll.initial-delay-ms=3600000"})
@Testcontainers
class FeedRepositoryIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try { VAULT = Files.createTempDirectory("obsidian-feed-vault"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          VAULT::toString);
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;

    @Autowired SourceRepository sources;
    @Autowired FeedRepository repo;
    long sid;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE feed_items, sources RESTART IDENTITY CASCADE");
        sid = sources.create("rss", "https://a/", null, "Blog A", null, null).id();
    }

    @Test
    void insertIfNew_dedupsOnItemUrl_whateverTheStatus() {
        Long id = repo.insertIfNew(sid, "https://a/1", "One", "https://i/t.jpg", 90, Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(id).isNotNull();
        repo.bin(id);
        assertThat(repo.insertIfNew(sid, "https://a/1", "One again", null, null, null)).isNull();
        var item = repo.get(id);
        assertThat(item.status()).isEqualTo("binned");
        assertThat(item.sourceTitle()).isEqualTo("Blog A");
        assertThat(item.thumbUrl()).isEqualTo("https://i/t.jpg");
        assertThat(item.durationS()).isEqualTo(90);
    }

    @Test
    void list_filtersByStatus_newestPublishedFirst_nullDatesLast() {
        Long old = repo.insertIfNew(sid, "https://a/old", "old", null, null, Instant.parse("2026-01-01T00:00:00Z"));
        Long nu = repo.insertIfNew(sid, "https://a/new", "new", null, null, Instant.parse("2026-06-01T00:00:00Z"));
        Long undated = repo.insertIfNew(sid, "https://a/nd", "nd", null, null, null);
        Long binned = repo.insertIfNew(sid, "https://a/b", "b", null, null, null);
        repo.bin(binned);
        assertThat(repo.list("pending", 50)).extracting(FeedRepository.FeedItem::id).containsExactly(nu, old, undated);
        assertThat(repo.list("binned", 50)).extracting(FeedRepository.FeedItem::id).containsExactly(binned);
        assertThat(repo.list("pending", 2)).hasSize(2);
    }

    @Test
    void claimAccepted_isAtomicOnce_markPendingClears() {
        Long id = repo.insertIfNew(sid, "https://a/1", "One", null, null, null);
        assertThat(repo.claimAccepted(id, "cap1")).isTrue();
        assertThat(repo.claimAccepted(id, "cap2")).isFalse();
        var item = repo.get(id);
        assertThat(item.status()).isEqualTo("accepted");
        assertThat(item.captureId()).isEqualTo("cap1");
        assertThat(item.decidedAt()).isNotNull();
        repo.markPending(id);
        item = repo.get(id);
        assertThat(item.status()).isEqualTo("pending");
        assertThat(item.captureId()).isNull();
        assertThat(item.decidedAt()).isNull();
    }

    @Test
    void bin_isIdempotent_andLeavesAcceptedAlone() {
        Long a = repo.insertIfNew(sid, "https://a/1", "1", null, null, null);
        repo.bin(a); repo.bin(a);
        assertThat(repo.get(a).status()).isEqualTo("binned");
        Long b = repo.insertIfNew(sid, "https://a/2", "2", null, null, null);
        repo.claimAccepted(b, "c");
        repo.bin(b);
        assertThat(repo.get(b).status()).isEqualTo("accepted");
    }

    @Test
    void initSchema_isIdempotent() {
        repo.initSchema();
        repo.initSchema();
    }
}
