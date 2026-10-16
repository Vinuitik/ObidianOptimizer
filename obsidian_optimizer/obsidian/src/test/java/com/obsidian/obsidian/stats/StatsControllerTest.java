package com.obsidian.obsidian.stats;

import com.obsidian.obsidian.cards.CardRepository;
import com.obsidian.obsidian.ml.PendingImageJobRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StatsController aggregates DB counts with two best-effort outbound HTTP calls
 * (embedder /ingest, wrapper /providers) that MUST degrade to {"up": false} rather
 * than ever failing the whole /stats response — a dashboard poll that 500s because
 * one downstream is briefly down would be worse than a stale/partial dashboard.
 * Real local HTTP servers stand in for the embedder/wrapper so the actual HttpClient
 * call + JSON parsing is exercised, not a mocked "it was called" assertion.
 */
class StatsControllerTest {

    JdbcTemplate jdbc;
    CardRepository cardRepo;
    PendingImageJobRepository imageJobRepo;
    StatsController controller;
    MockMvc mvc;
    HttpServer fakeEmbedder;
    HttpServer fakeWrapper;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        cardRepo = mock(CardRepository.class);
        imageJobRepo = mock(PendingImageJobRepository.class);
        controller = new StatsController(jdbc, cardRepo, imageJobRepo);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), (Class<Long>) org.mockito.ArgumentMatchers.eq(Long.class)))
            .thenReturn(10L, 4L, 50L, 7L); // notesTotal, notesEmbedded, chunksTotal, eligible — in call order
        when(cardRepo.stats()).thenReturn(Map.of("notes_with_cards", 3, "active_cards", 20, "archived_cards", 5));
        when(imageJobRepo.countByStatus()).thenReturn(Map.of("PENDING", 2, "DONE", 8));

        // Point both outbound calls at closed ports by default — each test that wants a
        // live fake server overrides the relevant URL after starting it.
        ReflectionTestUtils.setField(controller, "embedderUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(controller, "wrapperUrl", "http://127.0.0.1:1");
    }

    @AfterEach
    void tearDown() {
        if (fakeEmbedder != null) fakeEmbedder.stop(0);
        if (fakeWrapper != null) fakeWrapper.stop(0);
    }

    private HttpServer startServer(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return server;
    }

    @Test
    void stats_countsAreMappedToTheirExactFields_notMirroredOrSwapped() throws Exception {
        mvc.perform(get("/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.embedding.notesTotal").value(10))
            .andExpect(jsonPath("$.embedding.notesEmbedded").value(4))
            .andExpect(jsonPath("$.embedding.chunksTotal").value(50))
            .andExpect(jsonPath("$.images.pending").value(2))
            .andExpect(jsonPath("$.images.done").value(8))
            .andExpect(jsonPath("$.images.skipped").value(0))
            .andExpect(jsonPath("$.flashcards.eligibleNotes").value(7))
            .andExpect(jsonPath("$.flashcards.notesWithCards").value(3))
            .andExpect(jsonPath("$.flashcards.activeCards").value(20))
            .andExpect(jsonPath("$.flashcards.archivedCards").value(5));
    }

    @Test
    void stats_notesEmbeddedNeverExceedsNotesTotal_evenIfQueriesRaceOutOfOrder() throws Exception {
        // notesEmbedded (second query) returning MORE than notesTotal (first query) would
        // be a data race between the two COUNT queries; Math.min() must clamp it.
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), (Class<Long>) org.mockito.ArgumentMatchers.eq(Long.class)))
            .thenReturn(5L, 9L, 50L, 7L); // notesEmbedded(9) > notesTotal(5)

        mvc.perform(get("/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.embedding.notesTotal").value(5))
            .andExpect(jsonPath("$.embedding.notesEmbedded").value(5));
    }

    @Test
    void stats_bothDownstreamsUnreachable_degradesToUpFalse_insteadOf500() throws Exception {
        mvc.perform(get("/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resources.up").value(false))
            .andExpect(jsonPath("$.wrapper.up").value(false))
            .andExpect(jsonPath("$.wrapper.providers").doesNotExist());
    }

    @Test
    void stats_embedderUp_parsesJobCountsByStatus() throws Exception {
        fakeEmbedder = startServer("{\"jobs\":[{\"status\":\"DONE\"},{\"status\":\"DONE\"},{\"status\":\"QUEUED\"}]}");
        ReflectionTestUtils.setField(controller, "embedderUrl", "http://127.0.0.1:" + fakeEmbedder.getAddress().getPort());

        mvc.perform(get("/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resources.up").value(true))
            .andExpect(jsonPath("$.resources.counts.DONE").value(2))
            .andExpect(jsonPath("$.resources.counts.QUEUED").value(1));
    }

    @Test
    void stats_wrapperUp_exposesProvidersPayload() throws Exception {
        fakeWrapper = startServer("{\"openai\":{\"ok\":true}}");
        ReflectionTestUtils.setField(controller, "wrapperUrl", "http://127.0.0.1:" + fakeWrapper.getAddress().getPort());

        mvc.perform(get("/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.wrapper.up").value(true))
            .andExpect(jsonPath("$.wrapper.providers.openai.ok").value(true));
    }
}
