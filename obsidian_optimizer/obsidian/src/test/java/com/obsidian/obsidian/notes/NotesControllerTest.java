package com.obsidian.obsidian.notes;

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

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class NotesControllerTest {

    @Mock FileRepository fileRepository;

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new NotesController(fileRepository)).build();
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
        var page = new FileRepository.ReviewPageInfo(
            List.of(new FileRepository.ReviewNoteInfo("/v/A.md", true)), true);
        when(fileRepository.getReviewNotesPagedWithCards(0, 40)).thenReturn(page);
        mvc.perform(get("/review"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notes[0].path").value("/v/A.md"))
            .andExpect(jsonPath("$.notes[0].hasCards").value(true))
            .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void getReview_customOffsetAndLimit() throws Exception {
        var page = new FileRepository.ReviewPageInfo(List.of(), false);
        when(fileRepository.getReviewNotesPagedWithCards(10, 5)).thenReturn(page);
        mvc.perform(get("/review?offset=10&limit=5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(false));
        verify(fileRepository).getReviewNotesPagedWithCards(10, 5);
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
}
