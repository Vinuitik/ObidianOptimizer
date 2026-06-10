package com.obsidian.obsidian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MyControllerTest {

    @Mock FileRepository fileRepository;
    @Mock SettingsRepository settingsRepository;

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
            .standaloneSetup(new MyController(fileRepository, settingsRepository))
            .build();
    }

    // ── PATCH /notes/move ────────────────────────────────────────────────────

    @Test
    void moveNote_success_returns200WithNewPath() throws Exception {
        when(fileRepository.moveNote("/vault/FolderA/Note.md", "/vault/FolderB"))
            .thenReturn("/vault/FolderB/Note.md");

        mvc.perform(patch("/notes/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourcePath", "/vault/FolderA/Note.md",
                    "targetFolder", "/vault/FolderB"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/vault/FolderB/Note.md"));

        verify(fileRepository).moveNote("/vault/FolderA/Note.md", "/vault/FolderB");
    }

    @Test
    void moveNote_sourceNotFound_returns400() throws Exception {
        when(fileRepository.moveNote(anyString(), anyString()))
            .thenThrow(new IOException("Note not found: /vault/missing.md"));

        mvc.perform(patch("/notes/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourcePath", "/vault/missing.md",
                    "targetFolder", "/vault/FolderB"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void moveNote_targetFolderNotFound_returns400() throws Exception {
        when(fileRepository.moveNote(anyString(), anyString()))
            .thenThrow(new IOException("Target folder not found: /vault/NoSuchFolder"));

        mvc.perform(patch("/notes/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourcePath", "/vault/FolderA/Note.md",
                    "targetFolder", "/vault/NoSuchFolder"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void moveNote_filenameCollision_returns400() throws Exception {
        when(fileRepository.moveNote(anyString(), anyString()))
            .thenThrow(new IOException("A note named 'Note.md' already exists in the target folder"));

        mvc.perform(patch("/notes/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourcePath", "/vault/FolderA/Note.md",
                    "targetFolder", "/vault/FolderB"
                ))))
            .andExpect(status().isBadRequest());
    }

    // ── GET /settings ────────────────────────────────────────────────────────

    @Test
    void getSettings_returns200WithAllFields() throws Exception {
        when(settingsRepository.getVaultPath()).thenReturn("/vault");
        when(settingsRepository.getResourcePath()).thenReturn("/vault/resources/images");
        when(settingsRepository.getReviewPageSize()).thenReturn(20);
        when(settingsRepository.getStartupSyncMode()).thenReturn("blocking");

        mvc.perform(get("/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vaultPath").value("/vault"))
            .andExpect(jsonPath("$.resourcePath").value("/vault/resources/images"))
            .andExpect(jsonPath("$.reviewPageSize").value(20))
            .andExpect(jsonPath("$.startupSyncMode").value("blocking"));
    }

    // ── PUT /settings — reviewPageSize validation ─────────────────────────────

    @Test
    void updateSettings_reviewPageSizeZero_returns400() throws Exception {
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reviewPageSize", 0))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateSettings_reviewPageSize501_returns400() throws Exception {
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reviewPageSize", 501))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateSettings_reviewPageSizeBoundary1_returns200() throws Exception {
        when(settingsRepository.getVaultPath()).thenReturn("/v");
        when(settingsRepository.getResourcePath()).thenReturn("/v/r");
        when(settingsRepository.getReviewPageSize()).thenReturn(1);
        when(settingsRepository.getStartupSyncMode()).thenReturn("blocking");

        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reviewPageSize", 1))))
            .andExpect(status().isOk());
    }

    @Test
    void updateSettings_reviewPageSizeBoundary500_returns200() throws Exception {
        when(settingsRepository.getVaultPath()).thenReturn("/v");
        when(settingsRepository.getResourcePath()).thenReturn("/v/r");
        when(settingsRepository.getReviewPageSize()).thenReturn(500);
        when(settingsRepository.getStartupSyncMode()).thenReturn("blocking");

        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reviewPageSize", 500))))
            .andExpect(status().isOk());
    }

    // ── PUT /settings — startupSyncMode validation ───────────────────────────

    @Test
    void updateSettings_invalidSyncMode_returns400() throws Exception {
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("startupSyncMode", "instant"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateSettings_asyncSyncMode_returns200() throws Exception {
        when(settingsRepository.getVaultPath()).thenReturn("/v");
        when(settingsRepository.getResourcePath()).thenReturn("/v/r");
        when(settingsRepository.getReviewPageSize()).thenReturn(20);
        when(settingsRepository.getStartupSyncMode()).thenReturn("async");

        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("startupSyncMode", "async"))))
            .andExpect(status().isOk());
    }

    @Test
    void updateSettings_blockingSyncMode_returns200() throws Exception {
        when(settingsRepository.getVaultPath()).thenReturn("/v");
        when(settingsRepository.getResourcePath()).thenReturn("/v/r");
        when(settingsRepository.getReviewPageSize()).thenReturn(20);
        when(settingsRepository.getStartupSyncMode()).thenReturn("blocking");

        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("startupSyncMode", "blocking"))))
            .andExpect(status().isOk());
    }
}
