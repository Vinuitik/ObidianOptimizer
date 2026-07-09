package com.obsidian.obsidian.capture;

import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
            new CaptureController(repository, captureRepo, ingestWorker, settingsRepo)).build();
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
}
