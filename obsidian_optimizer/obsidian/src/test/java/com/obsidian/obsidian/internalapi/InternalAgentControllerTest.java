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
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/internal/tracks/{id}/items — the mini-course generation embedder read path.
 * Mocked TrackRepository, no DB (same convention as CaptureControllerTest): verifies the
 * token gate, the 404-on-missing-track guard, and that a valid call returns the track's items.
 */
@ExtendWith(MockitoExtension.class)
class InternalAgentControllerTest {

    private final com.obsidian.obsidian.vaults.ActiveVault activeVault = com.obsidian.obsidian.vaults.ActiveVault.forTest(1L, java.nio.file.Path.of("/vault"));

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
            fileRepository, settingsRepo, mediaController, captureRepo, trackRepo, activeVault);
        ReflectionTestUtils.setField(controller, "internalToken", TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void trackItems_validToken_returnsItems() throws Exception {
        Track track = new Track(1L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now(),
            null, null, null);
        TrackItem item1 = new TrackItem(1L, 1L, 0, "Ch1", "vault/Ch1.md", "pending", null, null);
        TrackItem item2 = new TrackItem(2L, 1L, 1, "Ch2", "vault/Ch2.md", "done", Instant.now(), null);
        when(trackRepo.get(1L, 1L)).thenReturn(track);
        when(trackRepo.listItems(1L)).thenReturn(List.of(item1, item2));

        mvc.perform(get("/api/internal/tracks/1/items").header("X-Internal-Token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Rust Book"))
            .andExpect(jsonPath("$.items[0].title").value("Ch1"))
            .andExpect(jsonPath("$.items[1].title").value("Ch2"))
            .andExpect(jsonPath("$.items[1].status").value("done"));
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
        when(trackRepo.get(org.mockito.ArgumentMatchers.eq(1L), eq(99L))).thenReturn(null);

        mvc.perform(get("/api/internal/tracks/99/items").header("X-Internal-Token", TOKEN))
            .andExpect(status().isNotFound());
    }

    // ── I1: raw intake no longer attaches to tracks ─────────────────────────────

    @Test
    void createNote_withCaptureId_neverTouchesTracks() throws Exception {
        activeVault.set(1L, java.nio.file.Path.of(String.valueOf("/tmp")));
        when(fileRepository.createNote(1L, "/tmp", "Note1")).thenReturn("/tmp/Note1.md");

        mvc.perform(post("/api/internal/notes")
                .header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"folder\":\"\",\"name\":\"Note1\",\"content\":\"hi\",\"captureId\":\"cap-1\"}"))
            .andExpect(status().isOk());

        org.mockito.Mockito.verifyNoInteractions(trackRepo);
    }

    @Test
    void postTrackItems_endpointRemoved() throws Exception {
        mvc.perform(post("/api/internal/tracks/7/items")
                .header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Chapter 1\",\"notePath\":\"vault/Ch1.md\",\"captureId\":\"cap-1\"}"))
            .andExpect(status().isMethodNotAllowed());

        verify(trackRepo, never()).addItem(anyLong(), anyString(), anyString(), any());
    }
}
