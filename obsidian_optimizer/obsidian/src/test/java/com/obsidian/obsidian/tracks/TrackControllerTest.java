package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TrackRepository.Track;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TrackController controller = new TrackController(
            trackRepo, todayPlan, reviewHandoff, progressService, trackAgentClient);
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
        Track track = new Track(1L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now());
        when(trackRepo.get(1L)).thenReturn(track);
        when(trackAgentClient.submitMinicourse(1L))
            .thenReturn(new TrackAgentClient.Result(true, 200, "{\"id\":\"job1\",\"status\":\"pending\"}"));

        mvc.perform(post("/tracks/1/minicourse"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"id\":\"job1\",\"status\":\"pending\"}"));
    }

    @Test
    void generateMinicourse_clientFailure_propagatesStatusAndBody() throws Exception {
        Track track = new Track(1L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now());
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
}
