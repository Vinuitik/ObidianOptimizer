package com.obsidian.obsidian.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.notes.FileRepository;
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

    @Mock SettingsRepository settingsRepository;
    @Mock FileRepository     fileRepository;

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
            .standaloneSetup(new SettingsController(settingsRepository, fileRepository))
            .build();
    }

    private void stubSettings() {
        when(settingsRepository.getVaultPath()).thenReturn("/v");
        when(settingsRepository.getResourcePath()).thenReturn("/v/r");
        when(settingsRepository.getReviewPageSize()).thenReturn(20);
        when(settingsRepository.getStartupSyncMode()).thenReturn("blocking");
        when(settingsRepository.getMaxDailyReviews()).thenReturn(30);
        when(settingsRepository.getBankruptcyLimit()).thenReturn(200);
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
}
