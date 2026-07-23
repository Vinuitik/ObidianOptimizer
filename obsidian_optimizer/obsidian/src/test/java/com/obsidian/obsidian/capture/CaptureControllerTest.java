package com.obsidian.obsidian.capture;

import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The shared-file capture endpoint ({@code POST /capture/file}) — the PWA share-sheet path
 * for PDFs / A/V. Verifies type classification (pdf/video/audio → enqueued standalone; other
 * → 415), the empty-file guard, that the bytes land under resources/files/, and that the
 * ingest worker is nudged. Mocked repo/worker — no DB, no embedder.
 */
@ExtendWith(MockitoExtension.class)
class CaptureControllerTest {

    @Mock FileRepository repository;
    @Mock CaptureRepository captureRepo;
    @Mock CaptureIngestWorker ingestWorker;
    @Mock SettingsRepository settingsRepo;

    @TempDir Path vault;
    private MockMvc mvc;
    private CaptureController controller;
    private HttpServer embedderStub;

    @BeforeEach
    void setUp() {
        controller = new CaptureController(repository, captureRepo, ingestWorker, settingsRepo);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        if (embedderStub != null) embedderStub.stop(0);
    }

    /** Starts a local stub embedder that always answers {@code /playlist/expand} with the
     *  given JSON body, and points the controller at it. Returns the bound port (unused). */
    private void stubPlaylistExpand(int status, String jsonBody) throws Exception {
        embedderStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embedderStub.createContext("/playlist/expand", exchange -> {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        embedderStub.start();
        ReflectionTestUtils.setField(controller, "embedderUrl",
            "http://127.0.0.1:" + embedderStub.getAddress().getPort());
    }

    @Test
    void captureFile_pdf_storesAndEnqueuesStandalone() throws Exception {
        when(settingsRepo.getVaultPath()).thenReturn(vault.toString());
        var pdf = new MockMultipartFile("file", "Intro to Agents.pdf",
            "application/pdf", "%PDF-1.4 body".getBytes());

        mvc.perform(multipart("/capture/file").file(pdf))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("queued"))
            .andExpect(jsonPath("$.captureId").isNotEmpty());

        verify(captureRepo).enqueue(anyString(), eq("pdf"),
            startsWith("resources/files/"), startsWith("resources/files/"), eq("Intro to Agents.pdf"));
        verify(ingestWorker).nudge();
        // the bytes actually landed as a vault resource
        try (var files = Files.list(vault.resolve("resources").resolve("files"))) {
            assertThat(files.anyMatch(p -> p.toString().endsWith(".pdf"))).isTrue();
        }
    }

    @Test
    void captureFile_video_classifiesAsVideo() throws Exception {
        when(settingsRepo.getVaultPath()).thenReturn(vault.toString());
        var vid = new MockMultipartFile("file", "lecture.mp4", "video/mp4", "binary".getBytes());

        mvc.perform(multipart("/capture/file").file(vid))
            .andExpect(status().isOk());

        verify(captureRepo).enqueue(anyString(), eq("video"), anyString(), anyString(), eq("lecture.mp4"));
    }

    @Test
    void captureFile_unsupportedType_is415AndNotEnqueued() throws Exception {
        var txt = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/capture/file").file(txt))
            .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(captureRepo, ingestWorker);
    }

    @Test
    void captureFile_empty_is400() throws Exception {
        var empty = new MockMultipartFile("file", "blank.pdf", "application/pdf", new byte[0]);

        mvc.perform(multipart("/capture/file").file(empty))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(captureRepo, ingestWorker);
    }

    // ── Playlist URL classification ──────────────────────────────────────────

    @Test
    void isPlaylistUrl_playlistPage_isTrue() {
        assertThat(CaptureController.isPlaylistUrl(
            "https://www.youtube.com/playlist?list=PL123")).isTrue();
    }

    @Test
    void isPlaylistUrl_singleVideoWithListParam_isFalse() {
        // A shared /watch?v=...&list=... link is a single video the user meant to
        // capture, not "expand the whole playlist".
        assertThat(CaptureController.isPlaylistUrl(
            "https://www.youtube.com/watch?v=abc&list=PL123")).isFalse();
    }

    @Test
    void isPlaylistUrl_plainVideoUrl_isFalse() {
        assertThat(CaptureController.isPlaylistUrl("https://youtu.be/abc")).isFalse();
    }

    @Test
    void isPlaylistUrl_nonVideoHost_isFalse() {
        assertThat(CaptureController.isPlaylistUrl(
            "https://example.com/playlist?list=PL123")).isFalse();
    }

    // ── Playlist expansion ───────────────────────────────────────────────────

    @Test
    void capture_playlistUrl_expandsIntoOneQueuedCapturePerVideo() throws Exception {
        stubPlaylistExpand(200, """
            {"entries":[
                {"url":"https://youtu.be/a","title":"Part 1"},
                {"url":"https://youtu.be/b","title":"Part 2"}
            ]}
            """);
        when(captureRepo.existsLiveForSource(anyString())).thenReturn(false);

        mvc.perform(post("/capture").contentType("application/json")
                .content("{\"url\":\"https://www.youtube.com/playlist?list=PL1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("queued"))
            .andExpect(jsonPath("$.playlistId").isNotEmpty())
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.skipped").value(0));

        verify(captureRepo).enqueuePlaylistItem(anyString(), eq("video"),
            eq("https://youtu.be/a"), isNull(), eq("Part 1"), anyString(), eq(0));
        verify(captureRepo).enqueuePlaylistItem(anyString(), eq("video"),
            eq("https://youtu.be/b"), isNull(), eq("Part 2"), anyString(), eq(1));
        verify(ingestWorker).nudge();
    }

    @Test
    void capture_playlistUrl_skipsEntriesAlreadyInPipeline() throws Exception {
        stubPlaylistExpand(200, """
            {"entries":[
                {"url":"https://youtu.be/a","title":"Part 1"},
                {"url":"https://youtu.be/b","title":"Part 2"}
            ]}
            """);
        when(captureRepo.existsLiveForSource("https://youtu.be/a")).thenReturn(true);
        when(captureRepo.existsLiveForSource("https://youtu.be/b")).thenReturn(false);

        mvc.perform(post("/capture").contentType("application/json")
                .content("{\"url\":\"https://www.youtube.com/playlist?list=PL1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.skipped").value(1));

        verify(captureRepo, never()).enqueuePlaylistItem(anyString(), anyString(),
            eq("https://youtu.be/a"), any(), anyString(), anyString(), anyInt());
        verify(captureRepo).enqueuePlaylistItem(anyString(), eq("video"),
            eq("https://youtu.be/b"), isNull(), eq("Part 2"), anyString(), eq(1));
    }

    @Test
    void capture_playlistUrl_embedderDown_returns502AndEnqueuesNothing() throws Exception {
        ReflectionTestUtils.setField(controller, "embedderUrl", "http://127.0.0.1:1");

        mvc.perform(post("/capture").contentType("application/json")
                .content("{\"url\":\"https://www.youtube.com/playlist?list=PL1\"}"))
            .andExpect(status().isBadGateway());

        verifyNoInteractions(captureRepo, ingestWorker);
    }

    @Test
    void capture_playlistUrl_embedderRejects_returns422() throws Exception {
        stubPlaylistExpand(422, "{\"detail\":\"not a playlist\"}");

        mvc.perform(post("/capture").contentType("application/json")
                .content("{\"url\":\"https://www.youtube.com/playlist?list=PL1\"}"))
            .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(captureRepo, ingestWorker);
    }
}
