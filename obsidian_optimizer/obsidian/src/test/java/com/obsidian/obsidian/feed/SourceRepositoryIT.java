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
        var s = repo.create("rss", "https://a/", "https://a/feed", "A", null, null);
        assertThat(s.autoAccept()).isNull();
        assertThat(s.status()).isEqualTo("active");
        assertThat(s.lastCheckedAt()).isNull();
        assertThat(repo.get(s.id())).isEqualTo(s);
        assertThat(repo.getByUrl("https://a/").id()).isEqualTo(s.id());
    }

    @Test
    void url_isUnique() {
        repo.create("rss", "https://a/", null, "A", null, null);
        assertThatThrownBy(() -> repo.create("rss", "https://a/", null, "A2", null, null)).isInstanceOf(Exception.class);
    }

    @Test
    void update_partialAndClearToInherit() {
        var s = repo.create("rss", "https://a/", null, "A", true, 5L);
        var u = repo.update(s.id(), null, false, null, false, "paused", "Renamed");
        assertThat(u.autoAccept()).isTrue();
        assertThat(u.targetTrackId()).isEqualTo(5L);
        assertThat(u.status()).isEqualTo("paused");
        assertThat(u.title()).isEqualTo("Renamed");
        var c = repo.update(s.id(), null, true, null, true, null, null);
        assertThat(c.autoAccept()).isNull();
        assertThat(c.targetTrackId()).isNull();
    }

    @Test
    void listActive_excludesPaused_markCheckedAdvances() {
        var a = repo.create("rss", "https://a/", null, "A", null, null);
        var b = repo.create("rss", "https://b/", null, "B", null, null);
        repo.update(b.id(), null, false, null, false, "paused", null);
        assertThat(repo.listActive()).extracting(SourceRepository.Source::id).containsExactly(a.id());
        Instant now = Instant.now();
        repo.markChecked(a.id(), now);
        assertThat(repo.get(a.id()).lastCheckedAt()).isNotNull();
    }

    @Test
    void delete_cascadesFeedItems_andPendingCountCounts(@Autowired FeedRepository feed) {
        var s = repo.create("rss", "https://a/", null, "A", null, null);
        feed.insertIfNew(s.id(), "https://a/1", "1", null, null, null);
        feed.insertIfNew(s.id(), "https://a/2", "2", null, null, null);
        assertThat(repo.get(s.id()).pendingCount()).isEqualTo(2);
        assertThat(repo.delete(s.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_items", Integer.class)).isZero();
        assertThat(repo.delete(s.id())).isFalse();
    }

    @Test
    void initSchema_isIdempotent() {
        repo.initSchema();
        repo.initSchema();
    }
}
