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
class SourceRepositoryIT {

    @org.springframework.beans.factory.annotation.Autowired com.obsidian.obsidian.vaults.ActiveVault activeVault;

    @org.junit.jupiter.api.BeforeEach
    void pinActiveVaultToTempRoot() { activeVault.set(1L, VAULT); }

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

    @Autowired SourceRepository repo;

    @BeforeEach
    void clean() { jdbc.execute("TRUNCATE feed_items, sources RESTART IDENTITY CASCADE"); }

    @Test
    void create_roundTrips_andAutoAcceptDefaultsNull() {
        var s = repo.create(1L, "rss", "https://a/", "https://a/feed", "A", null);
        assertThat(s.autoAccept()).isNull();
        assertThat(s.status()).isEqualTo("active");
        assertThat(s.lastCheckedAt()).isNull();
        assertThat(repo.get(1L, s.id())).isEqualTo(s);
        assertThat(repo.getByUrl(1L, "https://a/").id()).isEqualTo(s.id());
    }

    @Test
    void url_isUnique() {
        repo.create(1L, "rss", "https://a/", null, "A", null);
        assertThatThrownBy(() -> repo.create(1L, "rss", "https://a/", null, "A2", null)).isInstanceOf(Exception.class);
    }

    @Test
    void update_partialAndClearToInherit() {
        var s = repo.create(1L, "rss", "https://a/", null, "A", true);
        var u = repo.update(1L, s.id(), null, false, "paused", "Renamed");
        assertThat(u.autoAccept()).isTrue();
        assertThat(u.status()).isEqualTo("paused");
        assertThat(u.title()).isEqualTo("Renamed");
        var c = repo.update(1L, s.id(), null, true, null, null);
        assertThat(c.autoAccept()).isNull();
    }

    @Test
    void listActive_excludesPaused_markCheckedAdvances() {
        var a = repo.create(1L, "rss", "https://a/", null, "A", null);
        var b = repo.create(1L, "rss", "https://b/", null, "B", null);
        repo.update(1L, b.id(), null, false, "paused", null);
        assertThat(repo.listActive(1L)).extracting(SourceRepository.Source::id).containsExactly(a.id());
        Instant now = Instant.now();
        repo.markChecked(a.id(), now);
        assertThat(repo.get(1L, a.id()).lastCheckedAt()).isNotNull();
    }

    @Test
    void delete_cascadesFeedItems_andPendingCountCounts(@Autowired FeedRepository feed) {
        var s = repo.create(1L, "rss", "https://a/", null, "A", null);
        feed.insertIfNew(1L, s.id(), "https://a/1", "1", null, null, null);
        feed.insertIfNew(1L, s.id(), "https://a/2", "2", null, null, null);
        assertThat(repo.get(1L, s.id()).pendingCount()).isEqualTo(2);
        assertThat(repo.delete(1L, s.id())).isTrue();
        // soft delete: row kept (Bin), but invisible to every read, and its feed items are hidden with it
        assertThat(repo.get(1L, s.id())).isNull();
        assertThat(repo.list(1L)).isEmpty();
        assertThat(feed.list(1L, "pending", 50)).isEmpty();
        assertThat(repo.delete(1L, s.id())).isFalse();
        assertThat(repo.purge(1L, s.id())).isTrue();   // delete forever -> cascades
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_items", Integer.class)).isZero();
    }

    @Test
    void initSchema_isIdempotent() {
        repo.initSchema();
        repo.initSchema();
    }
}
