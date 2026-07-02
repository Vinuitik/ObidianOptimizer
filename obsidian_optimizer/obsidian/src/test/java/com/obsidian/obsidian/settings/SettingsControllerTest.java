package com.obsidian.obsidian.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.sync.DriveService;
import com.obsidian.obsidian.sync.VaultEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock SettingsRepository     settingsRepository;
    @Mock FileRepository         fileRepository;
    @Mock VaultEncryptionService encryptionService;
    @Mock DriveService           driveService;

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
            .standaloneSetup(new SettingsController(settingsRepository, fileRepository,
                                                    encryptionService, driveService))
            .build();
    }

    private void stubSettings() {
        when(settingsRepository.getVaultPath()).thenReturn("/v");
        when(settingsRepository.getResourcePath()).thenReturn("/v/r");
        when(settingsRepository.getReviewPageSize()).thenReturn(20);
        when(settingsRepository.getStartupSyncMode()).thenReturn("blocking");
        when(settingsRepository.getMaxDailyReviews()).thenReturn(30);
        when(settingsRepository.getBankruptcyLimit()).thenReturn(200);
        // getSettings() masks the secrets via .isBlank() — must not be null on mocks
        when(settingsRepository.getSyncClientSecret()).thenReturn("");
        when(settingsRepository.getSyncPassphrase()).thenReturn("");
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
        when(settingsRepository.getSyncClientSecret()).thenReturn("s3cret");
        when(settingsRepository.getSyncPassphrase()).thenReturn("");
        mvc.perform(get("/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vaultPath").value("/vault"))
            .andExpect(jsonPath("$.resourcePath").value("/vault/resources/images"))
            .andExpect(jsonPath("$.reviewPageSize").value(20))
            .andExpect(jsonPath("$.startupSyncMode").value("blocking"))
            .andExpect(jsonPath("$.maxDailyReviews").value(30))
            .andExpect(jsonPath("$.bankruptcyLimit").value(200))
            .andExpect(jsonPath("$.syncClientSecretSet").value(true))
            .andExpect(jsonPath("$.syncPassphraseSet").value(false));
    }

    // ── PUT /settings — sync fields ───────────────────────────────────────────

    @Test
    void updateSettings_syncPassphrase_savesAndReloadsKey() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("syncPassphrase", "hunter2"))))
            .andExpect(status().isOk());
        org.mockito.Mockito.verify(settingsRepository).set("syncPassphrase", "hunter2");
        org.mockito.Mockito.verify(encryptionService).reload();
    }

    @Test
    void updateSettings_blankSecret_isIgnored() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("syncClientSecret", ""))))
            .andExpect(status().isOk());
        org.mockito.Mockito.verify(settingsRepository, org.mockito.Mockito.never())
            .set(org.mockito.ArgumentMatchers.eq("syncClientSecret"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateSettings_syncClientId_savesAndResetsDrive() throws Exception {
        stubSettings();
        mvc.perform(put("/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("syncClientId", "abc.apps.googleusercontent.com"))))
            .andExpect(status().isOk());
        org.mockito.Mockito.verify(settingsRepository).set("syncClientId", "abc.apps.googleusercontent.com");
        org.mockito.Mockito.verify(driveService).reset();
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
}
