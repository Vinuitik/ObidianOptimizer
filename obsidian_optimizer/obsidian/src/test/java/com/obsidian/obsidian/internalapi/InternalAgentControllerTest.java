package com.obsidian.obsidian.internalapi;

import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.media.MediaController;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItemGroup;
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
        Track track = new Track(1L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now(),
            null, null, null);
        TrackItem item1 = new TrackItem(1L, 1L, 0, "Ch1", "vault/Ch1.md", "pending", null, null);
        TrackItem item2 = new TrackItem(2L, 1L, 1, "Ch2", "vault/Ch2.md", "done", Instant.now(), null);
        when(trackRepo.get(1L)).thenReturn(track);
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
        when(trackRepo.get(eq(99L))).thenReturn(null);

        mvc.perform(get("/api/internal/tracks/99/items").header("X-Internal-Token", TOKEN))
            .andExpect(status().isNotFound());
    }

    // ── linkToTrack (via createNote) — group-aware item add (Step 3a) ──────────

    @Test
    void createNote_captureHasTrackId_createsGroupThenAddsItemWithGroupId() throws Exception {
        when(settingsRepo.getVaultPath()).thenReturn("/tmp");
        when(fileRepository.createNote("/tmp", "Note1")).thenReturn("/tmp/Note1.md");
        CaptureRepository.Capture cap = new CaptureRepository.Capture(
            "cap-1", "video", "https://example.com/v1", null, "My Video", "processing",
            null, 0L, null, null, 7L, 0, null);
        when(captureRepo.get("cap-1")).thenReturn(cap);
        TrackItemGroup group = new TrackItemGroup(50L, 7L, "My Video", "https://example.com/v1", Instant.now());
        when(trackRepo.getOrCreateGroup(7L, "cap-1", "My Video", "https://example.com/v1")).thenReturn(group);

        mvc.perform(post("/api/internal/notes")
                .header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"folder\":\"\",\"name\":\"Note1\",\"content\":\"hi\",\"captureId\":\"cap-1\"}"))
            .andExpect(status().isOk());

        verify(trackRepo).getOrCreateGroup(7L, "cap-1", "My Video", "https://example.com/v1");
        verify(trackRepo).addItem(7L, "Note1", "/tmp/Note1.md", 50L);
    }

    @Test
    void createNote_captureHasNoTrackId_neitherGroupNorItemTouched() throws Exception {
        when(settingsRepo.getVaultPath()).thenReturn("/tmp");
        when(fileRepository.createNote("/tmp", "Note1")).thenReturn("/tmp/Note1.md");
        CaptureRepository.Capture cap = new CaptureRepository.Capture(
            "cap-2", "video", "https://example.com/v2", null, "Other Video", "processing",
            null, 0L, null, null, null, 0, null);
        when(captureRepo.get("cap-2")).thenReturn(cap);

        mvc.perform(post("/api/internal/notes")
                .header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"folder\":\"\",\"name\":\"Note1\",\"content\":\"hi\",\"captureId\":\"cap-2\"}"))
            .andExpect(status().isOk());

        verify(trackRepo, never()).getOrCreateGroup(anyLong(), anyString(), anyString(), any());
        verify(trackRepo, never()).addItem(anyLong(), anyString(), anyString(), any());
    }

    // ── POST /tracks/{id}/items (MCP-facing, Step 3a) ───────────────────────────

    @Test
    void addTrackItem_withCaptureId_createsGroupThenAddsItemWithGroupId() throws Exception {
        when(trackRepo.get(7L)).thenReturn(
            new Track(7L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now(), null, null, null));
        CaptureRepository.Capture cap = new CaptureRepository.Capture(
            "cap-1", "video", "https://example.com/v1", null, "My Video", "processing",
            null, 0L, null, null, 7L, 0, null);
        when(captureRepo.get("cap-1")).thenReturn(cap);
        TrackItemGroup group = new TrackItemGroup(50L, 7L, "My Video", "https://example.com/v1", Instant.now());
        when(trackRepo.getOrCreateGroup(7L, "cap-1", "My Video", "https://example.com/v1")).thenReturn(group);

        mvc.perform(post("/api/internal/tracks/7/items")
                .header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Chapter 1\",\"notePath\":\"vault/Ch1.md\",\"captureId\":\"cap-1\"}"))
            .andExpect(status().isOk());

        verify(trackRepo).getOrCreateGroup(7L, "cap-1", "My Video", "https://example.com/v1");
        verify(trackRepo).addItem(7L, "Chapter 1", "vault/Ch1.md", 50L);
    }

    @Test
    void addTrackItem_withoutCaptureId_usesThreeArgAddItem_noGroupTouched() throws Exception {
        when(trackRepo.get(7L)).thenReturn(
            new Track(7L, "Rust Book", "book", "active", "manual", null, null, true, Instant.now(), null, null, null));

        mvc.perform(post("/api/internal/tracks/7/items")
                .header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Chapter 1\",\"notePath\":\"vault/Ch1.md\"}"))
            .andExpect(status().isOk());

        verify(trackRepo).addItem(7L, "Chapter 1", "vault/Ch1.md");
        verify(trackRepo, never()).getOrCreateGroup(anyLong(), anyString(), anyString(), any());
    }
}
