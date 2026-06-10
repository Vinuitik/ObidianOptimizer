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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MyControllerTest {

    @Mock FileRepository     fileRepository;
    @Mock SettingsRepository settingsRepository;
    @Mock ChronoService      chronoService;

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
            .standaloneSetup(new MyController(fileRepository, settingsRepository, chronoService))
            .build();
    }

    // ── Helper: stub settings for responses that echo current settings ────────

    private void stubSettings() {
        when(settingsRepository.getVaultPath()).thenReturn("/v");
        when(settingsRepository.getResourcePath()).thenReturn("/v/r");
        when(settingsRepository.getReviewPageSize()).thenReturn(20);
        when(settingsRepository.getStartupSyncMode()).thenReturn("blocking");
        when(settingsRepository.getMaxDailyReviews()).thenReturn(30);
        when(settingsRepository.getBankruptcyLimit()).thenReturn(200);
    }

    // ── GET /names ────────────────────────────────────────────────────────────

    @Test
    void getNames_returns200WithList() throws Exception {
        when(fileRepository.getNoteNames()).thenReturn(new ArrayList<>(List.of("/v/A.md", "/v/B.md")));
        mvc.perform(get("/names"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("/v/A.md"))
            .andExpect(jsonPath("$[1]").value("/v/B.md"));
    }

    @Test
    void getNames_emptyVaultReturnsEmptyList() throws Exception {
        when(fileRepository.getNoteNames()).thenReturn(new ArrayList<>());
        mvc.perform(get("/names"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
    }

    // ── GET /review ───────────────────────────────────────────────────────────

    @Test
    void getReview_returns200WithPageAndHasMoreFlag() throws Exception {
        var page = new FileRepository.ReviewPage(List.of("/v/A.md"), true);
        when(fileRepository.getReviewNotesPaged(0, 40)).thenReturn(page);
        mvc.perform(get("/review"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notes[0]").value("/v/A.md"))
            .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void getReview_customOffsetAndLimit() throws Exception {
        var page = new FileRepository.ReviewPage(List.of(), false);
        when(fileRepository.getReviewNotesPaged(10, 5)).thenReturn(page);
        mvc.perform(get("/review?offset=10&limit=5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(false));
        verify(fileRepository).getReviewNotesPaged(10, 5);
    }

    // ── GET /text ─────────────────────────────────────────────────────────────

    @Test
    void getText_returns200WithContent() throws Exception {
        when(fileRepository.getText("/v/Note.md")).thenReturn("# Hello\n\nBody.");
        mvc.perform(get("/text?noteName=/v/Note.md"))
            .andExpect(status().isOk())
            .andExpect(content().string("# Hello\n\nBody."));
    }

    // ── POST /notes ───────────────────────────────────────────────────────────

    @Test
    void createNote_success_returns200WithPath() throws Exception {
        when(fileRepository.createNote("/v/Folder", "MyNote")).thenReturn("/v/Folder/MyNote.md");
        mvc.perform(post("/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("folder", "/v/Folder", "name", "MyNote"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/v/Folder/MyNote.md"));
    }

    @Test
    void createNote_alreadyExists_returns400() throws Exception {
        when(fileRepository.createNote(anyString(), anyString()))
            .thenThrow(new IOException("Note already exists: MyNote.md"));
        mvc.perform(post("/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("folder", "/v", "name", "MyNote"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createNote_folderNotFound_returns400() throws Exception {
        when(fileRepository.createNote(anyString(), anyString()))
            .thenThrow(new IOException("Folder not found: /v/Missing"));
        mvc.perform(post("/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("folder", "/v/Missing", "name", "Note"))))
            .andExpect(status().isBadRequest());
    }

    // ── PATCH /notes/content ──────────────────────────────────────────────────

    @Test
    void patchNote_success_returns200() throws Exception {
        doNothing().when(fileRepository).patchNote(anyString(), anyList());
        mvc.perform(patch("/notes/content")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "path", "/v/Note.md",
                    "hunks", List.of(Map.of("startLine", 1, "deleteCount", 1, "insertLines", List.of("new line")))
                ))))
            .andExpect(status().isOk());
        verify(fileRepository).patchNote(anyString(), anyList());
    }

    @Test
    void patchNote_noteNotFound_returns400() throws Exception {
        doThrow(new IOException("Note not found: /v/ghost.md"))
            .when(fileRepository).patchNote(anyString(), anyList());
        mvc.perform(patch("/notes/content")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "path", "/v/ghost.md",
                    "hunks", List.of()
                ))))
            .andExpect(status().isBadRequest());
    }

    // ── PATCH /notes/rename ───────────────────────────────────────────────────

    @Test
    void renameNote_success_returns200WithNewPath() throws Exception {
        when(fileRepository.renameNote("/v/Old.md", "New")).thenReturn("/v/New.md");
        mvc.perform(patch("/notes/rename")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("oldPath", "/v/Old.md", "newName", "New"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/v/New.md"));
    }

    @Test
    void renameNote_collision_returns400() throws Exception {
        when(fileRepository.renameNote(anyString(), anyString()))
            .thenThrow(new IOException("A note named 'New' already exists in this folder"));
        mvc.perform(patch("/notes/rename")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("oldPath", "/v/Old.md", "newName", "New"))))
            .andExpect(status().isBadRequest());
    }

    // ── DELETE /notes ─────────────────────────────────────────────────────────

    @Test
    void deleteNote_success_returns200() throws Exception {
        doNothing().when(fileRepository).softDeleteNote("/v/Note.md");
        mvc.perform(delete("/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("path", "/v/Note.md"))))
            .andExpect(status().isOk());
        verify(fileRepository).softDeleteNote("/v/Note.md");
    }

    @Test
    void deleteNote_notFound_returns400() throws Exception {
        doThrow(new IOException("Note not found: /v/ghost.md"))
            .when(fileRepository).softDeleteNote(anyString());
        mvc.perform(delete("/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("path", "/v/ghost.md"))))
            .andExpect(status().isBadRequest());
    }

    // ── PATCH /notes/move ─────────────────────────────────────────────────────

    @Test
    void moveNote_success_returns200WithNewPath() throws Exception {
        when(fileRepository.moveNote("/v/FolderA/Note.md", "/v/FolderB"))
            .thenReturn("/v/FolderB/Note.md");
        mvc.perform(patch("/notes/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourcePath", "/v/FolderA/Note.md",
                    "targetFolder", "/v/FolderB"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/v/FolderB/Note.md"));
        verify(fileRepository).moveNote("/v/FolderA/Note.md", "/v/FolderB");
    }

    @Test
    void moveNote_sourceNotFound_returns400() throws Exception {
        when(fileRepository.moveNote(anyString(), anyString()))
            .thenThrow(new IOException("Note not found: /v/missing.md"));
        mvc.perform(patch("/notes/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourcePath", "/v/missing.md",
                    "targetFolder", "/v/FolderB"
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void moveNote_targetFolderNotFound_returns400() throws Exception {
        when(fileRepository.moveNote(anyString(), anyString()))
            .thenThrow(new IOException("Target folder not found: /v/NoSuch"));
        mvc.perform(patch("/notes/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourcePath", "/v/Note.md",
                    "targetFolder", "/v/NoSuch"
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
                    "sourcePath", "/v/A/Note.md",
                    "targetFolder", "/v/B"
                ))))
            .andExpect(status().isBadRequest());
    }

    // ── GET /settings ─────────────────────────────────────────────────────────

    @Test
    void getSettings_returns200WithAllFields() throws Exception {
        when(settingsRepository.getVaultPath()).thenReturn("/vault");
        when(settingsRepository.getResourcePath()).thenReturn("/vault/resources/images");
        when(settingsRepository.getReviewPageSize()).thenReturn(20);
        when(settingsRepository.getStartupSyncMode()).thenReturn("blocking");
        when(settingsRepository.getMaxDailyReviews()).thenReturn(30);
        when(settingsRepository.getBankruptcyLimit()).thenReturn(200);
        mvc.perform(get("/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vaultPath").value("/vault"))
            .andExpect(jsonPath("$.resourcePath").value("/vault/resources/images"))
            .andExpect(jsonPath("$.reviewPageSize").value(20))
            .andExpect(jsonPath("$.startupSyncMode").value("blocking"))
            .andExpect(jsonPath("$.maxDailyReviews").value(30))
            .andExpect(jsonPath("$.bankruptcyLimit").value(200));
    }

    // ── PUT /settings — reviewPageSize ────────────────────────────────────────

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
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reviewPageSize", 1))))
            .andExpect(status().isOk());
    }

    @Test
    void updateSettings_reviewPageSizeBoundary500_returns200() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reviewPageSize", 500))))
            .andExpect(status().isOk());
    }

    // ── PUT /settings — startupSyncMode ──────────────────────────────────────

    @Test
    void updateSettings_invalidSyncMode_returns400() throws Exception {
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("startupSyncMode", "instant"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateSettings_asyncSyncMode_returns200() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("startupSyncMode", "async"))))
            .andExpect(status().isOk());
    }

    @Test
    void updateSettings_blockingSyncMode_returns200() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("startupSyncMode", "blocking"))))
            .andExpect(status().isOk());
    }

    // ── PUT /settings — maxDailyReviews ──────────────────────────────────────

    @Test
    void updateSettings_maxDailyReviewsZero_returns400() throws Exception {
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("maxDailyReviews", 0))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateSettings_maxDailyReviewsOne_returns200() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("maxDailyReviews", 1))))
            .andExpect(status().isOk());
    }

    @Test
    void updateSettings_maxDailyReviewsLargeValue_returns200() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("maxDailyReviews", 10000))))
            .andExpect(status().isOk());
    }

    // ── PUT /settings — bankruptcyLimit ──────────────────────────────────────

    @Test
    void updateSettings_bankruptcyLimitZero_returns400() throws Exception {
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("bankruptcyLimit", 0))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateSettings_bankruptcyLimitOne_returns200() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("bankruptcyLimit", 1))))
            .andExpect(status().isOk());
    }

    // ── GET /chrono/status ────────────────────────────────────────────────────

    @Test
    void getChronoStatus_returns200WithLastRunDate() throws Exception {
        when(chronoService.getLastRunDate()).thenReturn("2026-06-10");
        mvc.perform(get("/chrono/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lastRunDate").value("2026-06-10"));
    }

    @Test
    void getChronoStatus_neverRunReturnsEmpty() throws Exception {
        when(chronoService.getLastRunDate()).thenReturn("");
        mvc.perform(get("/chrono/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lastRunDate").value(""));
    }

    // ── POST /chrono/run ──────────────────────────────────────────────────────

    @Test
    void runChrono_success_returns200WithResult() throws Exception {
        var bankruptcy = new BankruptcyService.BankruptcyResult(5, true, 5);
        var spread = new SpreadService.SpreadResult(20, 3);
        var result = new ChronoService.ChronoResult("2026-06-10", 2, 1, bankruptcy, spread);
        when(chronoService.runAllJobs()).thenReturn(result);
        mvc.perform(post("/chrono/run"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filesMoved").value(2))
            .andExpect(jsonPath("$.filesFixed").value(1))
            .andExpect(jsonPath("$.bankruptcy.declared").value(true))
            .andExpect(jsonPath("$.spread.moved").value(3));
    }

    @Test
    void runChrono_exceptionReturns500() throws Exception {
        when(chronoService.runAllJobs()).thenThrow(new RuntimeException("disk full"));
        mvc.perform(post("/chrono/run"))
            .andExpect(status().isInternalServerError());
    }
}
