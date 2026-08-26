package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mini-course generation proxy on TrackController — mocked TrackRepository +
 * TrackAgentClient, no DB/HTTP (same standalone-MockMvc convention as
 * InternalAgentControllerTest). Confirms the 404-on-missing-track guard, that a
 * downstream failure's real status/body is propagated (not swallowed as a 500), and
 * that approve's approvedIndexes (including the omitted/null = approve-all case)
 * reaches TrackAgentClient correctly.
 */
@ExtendWith(MockitoExtension.class)
class TrackControllerTest {

    @Mock TrackRepository trackRepo;
    @Mock TodayPlanService todayPlan;
    @Mock TrackReviewHandoff reviewHandoff;
    @Mock TrackProgressService progressService;
    @Mock TrackAgentClient trackAgentClient;
    @Mock SubscriptionPollWorker subscriptionPollWorker;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TrackController controller = new TrackController(
            trackRepo, todayPlan, reviewHandoff, progressService, trackAgentClient, subscriptionPollWorker);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void generateMinicourse_unknownTrack_returnsNotFound_clientNeverCalled() throws Exception {
        when(trackRepo.get(99L)).thenReturn(null);

        mvc.perform(post("/tracks/99/minicourse"))
            .andExpect(status().isNotFound());

        verify(trackAgentClient, never()).submitMinicourse(anyLong());
    }

    @Test
    void generateMinicourse_success_returnsJobBody() throws Exception {
        Track track = new Track(1L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now(),
            null, null, null);
        when(trackRepo.get(1L)).thenReturn(track);
        when(trackAgentClient.submitMinicourse(1L))
            .thenReturn(new TrackAgentClient.Result(true, 200, "{\"id\":\"job1\",\"status\":\"pending\"}"));

        mvc.perform(post("/tracks/1/minicourse"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"id\":\"job1\",\"status\":\"pending\"}"));
    }

    @Test
    void generateMinicourse_clientFailure_propagatesStatusAndBody() throws Exception {
        Track track = new Track(1L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now(),
            null, null, null);
        when(trackRepo.get(1L)).thenReturn(track);
        when(trackAgentClient.submitMinicourse(1L))
            .thenReturn(new TrackAgentClient.Result(false, 503, "{\"error\":\"embedder unreachable\"}"));

        mvc.perform(post("/tracks/1/minicourse"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"error\":\"embedder unreachable\"}"));
    }

    @Test
    void pollMinicourse_happyPath_returnsJobBody() throws Exception {
        when(trackAgentClient.pollMinicourse("job1"))
            .thenReturn(new TrackAgentClient.Result(true, 200, "{\"id\":\"job1\",\"status\":\"done\"}"));

        mvc.perform(get("/tracks/minicourse/job1"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"id\":\"job1\",\"status\":\"done\"}"));
    }

    @Test
    void pollMinicourse_notFoundFromEmbedder_propagates404() throws Exception {
        when(trackAgentClient.pollMinicourse("missing"))
            .thenReturn(new TrackAgentClient.Result(false, 404, "{\"error\":\"not found\"}"));

        mvc.perform(get("/tracks/minicourse/missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void approveMinicourse_withIndexes_reachesClient() throws Exception {
        when(trackAgentClient.approveMinicourse(eq("job1"), eq(List.of(0, 2))))
            .thenReturn(new TrackAgentClient.Result(true, 200, "{\"id\":\"job1\",\"status\":\"approved\"}"));

        mvc.perform(post("/tracks/minicourse/job1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approvedIndexes\":[0,2]}"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"id\":\"job1\",\"status\":\"approved\"}"));
    }

    @Test
    void approveMinicourse_nullIndexes_meansApproveAll() throws Exception {
        when(trackAgentClient.approveMinicourse(eq("job1"), isNull()))
            .thenReturn(new TrackAgentClient.Result(true, 200, "{\"id\":\"job1\",\"status\":\"approved\"}"));

        mvc.perform(post("/tracks/minicourse/job1/approve"))
            .andExpect(status().isOk());
    }

    @Test
    void importCsv_success_returnsPreviewBody() throws Exception {
        when(trackAgentClient.importCsv("title,type\nRust Book,book"))
            .thenReturn(new TrackAgentClient.Result(true, 200,
                "{\"tracks\":[{\"title\":\"Rust Book\",\"type\":\"book\"}],\"items\":[]}"));

        mvc.perform(post("/tracks/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"csvText\":\"title,type\\nRust Book,book\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"tracks\":[{\"title\":\"Rust Book\",\"type\":\"book\"}],\"items\":[]}"));
    }

    @Test
    void importCsv_embedderRejectsCsv_propagates422AndBody() throws Exception {
        when(trackAgentClient.importCsv("garbage"))
            .thenReturn(new TrackAgentClient.Result(false, 422, "{\"error\":\"unrecognized CSV shape\"}"));

        mvc.perform(post("/tracks/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"csvText\":\"garbage\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"error\":\"unrecognized CSV shape\"}"));
    }

    @Test
    void commitImport_createsTracksAndItems_completesDoneItem() throws Exception {
        Track trackA = new Track(10L, "Track A", "book", "active", "excel_import", null, null, true, Instant.now(),
            null, null, null);
        Track trackB = new Track(20L, "Track B", "custom", "active", "excel_import", null, null, true, Instant.now(),
            null, null, null);
        when(trackRepo.create("Track A", "book", "excel_import")).thenReturn(trackA);
        when(trackRepo.create("Track B", "custom", "excel_import")).thenReturn(trackB);

        TrackItem item1 = new TrackItem(100L, 10L, 0, "Item 1", null, "pending", null, null);
        TrackItem item2 = new TrackItem(101L, 10L, 1, "Item 2", null, "pending", null, null);
        TrackItem item3 = new TrackItem(102L, 20L, 0, "Item 3", null, "pending", null, null);
        when(trackRepo.addItem(10L, "Item 1", null)).thenReturn(item1);
        when(trackRepo.addItem(10L, "Item 2", null)).thenReturn(item2);
        when(trackRepo.addItem(20L, "Item 3", null)).thenReturn(item3);

        String body = """
            {
              "tracks": [
                {"title":"Track A","type":"book"},
                {"title":"Track B","type":null}
              ],
              "items": [
                {"trackIndex":0,"title":"Item 1","status":"pending"},
                {"trackIndex":0,"title":"Item 2","status":"done"},
                {"trackIndex":1,"title":"Item 3","status":"pending"}
              ]
            }
            """;

        mvc.perform(post("/tracks/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tracksCreated").value(2))
            .andExpect(jsonPath("$.itemsCreated").value(3));

        verify(trackRepo).create("Track A", "book", "excel_import");
        verify(trackRepo).create("Track B", "custom", "excel_import");
        verify(trackRepo).addItem(10L, "Item 1", null);
        verify(trackRepo).addItem(10L, "Item 2", null);
        verify(trackRepo).addItem(20L, "Item 3", null);
        verify(trackRepo, times(1)).completeItem(101L);
        verify(trackRepo, times(1)).completeItem(anyLong());
    }

    @Test
    void commitImport_outOfRangeTrackIndex_skippedNotFiveHundred() throws Exception {
        Track trackA = new Track(10L, "Track A", "book", "active", "excel_import", null, null, true, Instant.now(),
            null, null, null);
        when(trackRepo.create("Track A", "book", "excel_import")).thenReturn(trackA);

        TrackItem validItem = new TrackItem(100L, 10L, 0, "Valid Item", null, "pending", null, null);
        when(trackRepo.addItem(10L, "Valid Item", null)).thenReturn(validItem);

        String body = """
            {
              "tracks": [
                {"title":"Track A","type":"book"}
              ],
              "items": [
                {"trackIndex":5,"title":"Orphan Item","status":"pending"},
                {"trackIndex":0,"title":"Valid Item","status":"pending"}
              ]
            }
            """;

        mvc.perform(post("/tracks/import/commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tracksCreated").value(1))
            .andExpect(jsonPath("$.itemsCreated").value(2));

        verify(trackRepo).addItem(10L, "Valid Item", null);
        verify(trackRepo, never()).addItem(eq(10L), eq("Orphan Item"), isNull());
    }

    // ── Subscription poll-now (Step 6) ──────────────────────────────────────────

    @Test
    void pollNow_subscriptionTrack_returnsOk() throws Exception {
        when(subscriptionPollWorker.pollNow(1L)).thenReturn(true);

        mvc.perform(post("/tracks/1/poll-now"))
            .andExpect(status().isOk());
    }

    @Test
    void pollNow_nonSubscriptionTrack_returnsBadRequest() throws Exception {
        when(subscriptionPollWorker.pollNow(1L)).thenReturn(false);

        mvc.perform(post("/tracks/1/poll-now"))
            .andExpect(status().isBadRequest());
    }
}
