package com.obsidian.obsidian.workspace;

import com.obsidian.obsidian.settings.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The vault is a real temp directory (not mocked) so filename-sanitization and the
 *  path-traversal guard are exercised against the real filesystem, not an assumption
 *  about what Files.write would have done. */
class WorkspaceControllerTest {

    private final com.obsidian.obsidian.vaults.ActiveVault activeVault = com.obsidian.obsidian.vaults.ActiveVault.forTest(1L, java.nio.file.Path.of("/vault"));

    @TempDir Path vault;

    SettingsRepository settingsRepo;
    WorkspaceRepository workspaceRepo;
    MockMvc mvc;
    Path workspaceDir;

    @BeforeEach
    void setUp() {
        settingsRepo = mock(SettingsRepository.class);
        workspaceRepo = mock(WorkspaceRepository.class);
        activeVault.set(1L, java.nio.file.Path.of(String.valueOf(vault.toString())));
        workspaceDir = vault.resolve("_workspace");
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(settingsRepo, workspaceRepo, activeVault)).build();
    }

    // ── /workspace/upload ────────────────────────────────────────────────────────

    @Test
    void upload_allowedExtension_storesFileAndInsertsVideoType() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "lecture.mp4", "video/mp4", "bytes".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/workspace/upload").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filename").value("lecture.mp4"));

        assertThat(Files.readString(workspaceDir.resolve("lecture.mp4"))).isEqualTo("bytes");
        verify(workspaceRepo).insert(1L, "lecture.mp4", "video", null, 0);
    }

    @Test
    void upload_disallowedExtension_rejectedBeforeTouchingDiskOrRepo() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "script.exe", "application/octet-stream", "x".getBytes());

        mvc.perform(multipart("/workspace/upload").file(file))
            .andExpect(status().isBadRequest());

        assertThat(Files.exists(workspaceDir.resolve("script.exe"))).isFalse();
        verifyNoInteractions(workspaceRepo);
    }

    @Test
    void upload_filenameWithPathTraversal_sanitizedToSingleSegmentUnderWorkspaceDir() throws Exception {
        // Browsers can send a full path as originalFilename; "../../evil.mp4" must land
        // INSIDE _workspace/ as a plain filename, never escape it onto the vault root.
        MockMultipartFile file = new MockMultipartFile("file", "../../evil.mp4", "video/mp4", "x".getBytes());

        mvc.perform(multipart("/workspace/upload").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filename").value("evil.mp4"));

        assertThat(Files.exists(vault.resolve("evil.mp4"))).isFalse(); // did not escape _workspace/
        assertThat(Files.readString(workspaceDir.resolve("evil.mp4"))).isEqualTo("x");
    }

    @Test
    void upload_oversizedFile_rejectedWithoutWritingToDisk() throws Exception {
        MockMultipartFile file = spyOversized("huge.mp4", "video/mp4");

        mvc.perform(multipart("/workspace/upload").file(file))
            .andExpect(status().isBadRequest());

        assertThat(Files.exists(workspaceDir.resolve("huge.mp4"))).isFalse();
    }

    private static MockMultipartFile spyOversized(String name, String contentType) {
        return new MockMultipartFile("file", name, contentType, new byte[1]) {
            @Override
            public long getSize() {
                return 512L * 1024 * 1024 + 1; // just over the 512MB cap
            }
        };
    }

    @Test
    void upload_emptyFile_rejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.mp4", "video/mp4", new byte[0]);

        mvc.perform(multipart("/workspace/upload").file(file))
            .andExpect(status().isBadRequest());
    }

    @Test
    void upload_reuploadingSameFilename_upsertsRatherThanDuplicating() throws Exception {
        MockMultipartFile v1 = new MockMultipartFile("file", "notes.pdf", "application/pdf", "v1".getBytes());
        MockMultipartFile v2 = new MockMultipartFile("file", "notes.pdf", "application/pdf", "version-two".getBytes());

        mvc.perform(multipart("/workspace/upload").file(v1)).andExpect(status().isOk());
        mvc.perform(multipart("/workspace/upload").file(v2)).andExpect(status().isOk());

        assertThat(Files.readString(workspaceDir.resolve("notes.pdf"))).isEqualTo("version-two");
        verify(workspaceRepo, times(2)).insert(1L, "notes.pdf", "pdf", null, 0);
    }

    // ── /workspace/save (URL validation only — no outbound network in a unit test) ──

    @Test
    void save_missingUrl_returns400() throws Exception {
        mvc.perform(post("/workspace/save").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void save_nonHttpScheme_rejectedBeforeAnyNetworkCall() throws Exception {
        mvc.perform(post("/workspace/save").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"file:///etc/passwd\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("http/https")));
        verifyNoInteractions(workspaceRepo);
    }

    @Test
    void save_malformedUrl_returns400() throws Exception {
        mvc.perform(post("/workspace/save").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ht!tp://not a url\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── /workspace/files ─────────────────────────────────────────────────────────

    @Test
    void listFiles_prunesRowsWhoseFileWasDeletedByHand() throws Exception {
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("present.pdf"), "x");
        when(workspaceRepo.listAll(1L)).thenReturn(List.of(
            new WorkspaceRepository.WorkspaceItem("present.pdf", "pdf", null, 0, 1L),
            new WorkspaceRepository.WorkspaceItem("missing.pdf", "pdf", null, 0, 2L)));

        mvc.perform(get("/workspace/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("present.pdf"));

        verify(workspaceRepo).delete(1L, "missing.pdf");
        verify(workspaceRepo, never()).delete(1L, "present.pdf");
    }
}
