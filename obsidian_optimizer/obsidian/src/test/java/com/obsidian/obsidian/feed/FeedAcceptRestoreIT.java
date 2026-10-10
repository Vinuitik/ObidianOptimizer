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
class FeedAcceptRestoreIT {

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
    @Autowired FeedRepository feed;
    @Autowired FeedService service;
    @Autowired com.obsidian.obsidian.capture.CaptureRepository captures;
    @Autowired com.obsidian.obsidian.tracks.TrackRepository tracks;
    long sid;
    long itemId;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE feed_items, sources RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE capture CASCADE");
        sid = sources.create("youtube", "https://www.youtube.com/@x", null, "X", null, null).id();
        itemId = feed.insertIfNew(sid, "https://www.youtube.com/watch?v=abcdefghijk", "Vid", null, null, null);
    }

    @Test
    void pollInsertsNoCaptures_acceptCreatesExactlyOne_taggedWithPassiveTrack() {
        assertThat(captures.listAll()).isEmpty();

        String cap = service.accept(itemId);

        assertThat(captures.listAll()).hasSize(1);
        var c = captures.get(cap);
        assertThat(c.status()).isEqualTo("queued");
        assertThat(c.sourceRef()).isEqualTo("https://www.youtube.com/watch?v=abcdefghijk");
        assertThat(c.trackId()).isEqualTo(tracks.getOrCreatePassive().id());
        assertThat(tracks.getOrCreatePassive().priority()).isEqualTo("could");
        assertThat(feed.get(itemId).captureId()).isEqualTo(cap);
    }

    @Test
    void doubleAccept_returnsSameCapture_stillOneRow() {
        String a = service.accept(itemId);
        String b = service.accept(itemId);
        assertThat(b).isEqualTo(a);
        assertThat(captures.listAll()).hasSize(1);
    }

    @Test
    void accept_usesSourceTargetTrack_whenSet() {
        long tr = tracks.create("Mine", "custom", "manual").id();
        sources.update(sid, null, false, tr, false, null, null);
        assertThat(captures.get(service.accept(itemId)).trackId()).isEqualTo(tr);
    }

    @Test
    void accept_unknownItem_returnsNull() {
        assertThat(service.accept(99999)).isNull();
    }

    @Test
    void restore_whileQueued_cancelsCapture_backToPending_reAcceptable() {
        String cap = service.accept(itemId);

        service.restore(itemId);

        assertThat(feed.get(itemId).status()).isEqualTo("pending");
        assertThat(feed.get(itemId).captureId()).isNull();
        assertThat(captures.get(cap).status()).isEqualTo("discarded");
        String again = service.accept(itemId);
        assertThat(again).isNotEqualTo(cap);
    }

    @Test
    void restore_afterCaptureLeftQueued_throws409Condition_andStaysAccepted() {
        String cap = service.accept(itemId);
        captures.claim(cap);   // ingest worker took it

        assertThatThrownBy(() -> service.restore(itemId))
            .isInstanceOf(FeedService.CaptureStartedException.class);
        assertThat(feed.get(itemId).status()).isEqualTo("accepted");
    }

    @Test
    void restore_fromBinned_backToPending() {
        feed.bin(itemId);
        service.restore(itemId);
        assertThat(feed.get(itemId).status()).isEqualTo("pending");
    }

    @Test
    void autoAcceptedItemFromBinned_acceptIngestAnyway() {
        feed.bin(itemId);
        assertThat(service.accept(itemId)).isNotNull();
        assertThat(feed.get(itemId).status()).isEqualTo("accepted");
    }

    @Test
    void controller_restoreMapsConflictTo409_acceptIs200WithCaptureId() throws Exception {
        var ctl = new FeedController(feed, sources, service, null, null);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(ctl).build();
        var accept = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/feed/items/" + itemId + "/accept"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.captureId").exists())
            .andReturn();
        String cap = feed.get(itemId).captureId();
        assertThat(accept.getResponse().getContentAsString()).contains(cap);
        captures.claim(cap);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/feed/items/" + itemId + "/restore"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/feed/items/" + itemId + "/bin"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }
}
