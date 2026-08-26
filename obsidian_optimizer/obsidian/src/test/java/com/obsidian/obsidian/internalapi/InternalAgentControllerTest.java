package com.obsidian.obsidian.internalapi;

import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.media.MediaController;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/internal/tracks/{id}/items — the mini-course generation embedder read path.
 * Mocked TrackRepository, no DB (same convention as CaptureControllerTest): verifies the
 * token gate, the 404-on-missing-track guard, and that a valid call returns the track's items.
 */
@ExtendWith(MockitoExtension.class)
class InternalAgentControllerTest {

    private static final String TOKEN = "test-internal-token";

    @Mock FileRepository fileRepository;
    @Mock SettingsRepository settingsRepo;
    @Mock MediaController mediaController;
    @Mock CaptureRepository captureRepo;
    @Mock TrackRepository trackRepo;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        InternalAgentController controller = new InternalAgentController(
            fileRepository, settingsRepo, mediaController, captureRepo, trackRepo);
        ReflectionTestUtils.setField(controller, "internalToken", TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void trackItems_validToken_returnsItems() throws Exception {
        Track track = new Track(1L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now());
        TrackItem item1 = new TrackItem(1L, 1L, 0, "Ch1", "vault/Ch1.md", "pending", null);
        TrackItem item2 = new TrackItem(2L, 1L, 1, "Ch2", "vault/Ch2.md", "done", Instant.now());
        when(trackRepo.get(1L)).thenReturn(track);
        when(trackRepo.listItems(1L)).thenReturn(List.of(item1, item2));

        mvc.perform(get("/api/internal/tracks/1/items").header("X-Internal-Token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Ch1"))
            .andExpect(jsonPath("$[1].title").value("Ch2"))
            .andExpect(jsonPath("$[1].status").value("done"));
    }

    @Test
    void trackItems_badToken_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/internal/tracks/1/items").header("X-Internal-Token", "wrong-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void trackItems_missingToken_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/internal/tracks/1/items"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void trackItems_unknownTrack_returnsNotFound() throws Exception {
        when(trackRepo.get(eq(99L))).thenReturn(null);

        mvc.perform(get("/api/internal/tracks/99/items").header("X-Internal-Token", TOKEN))
            .andExpect(status().isNotFound());
    }
}
