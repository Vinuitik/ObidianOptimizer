package com.obsidian.obsidian.chrono;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ChronoControllerTest {

    @Mock ChronoService chronoService;

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ChronoController(chronoService)).build();
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
