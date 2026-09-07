package com.obsidian.obsidian.pwa;

import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.DeviceIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// POST /pwa/export used to only rebuild the review + cards bundles, never the inbox one —
// so a phone reconnecting mid-day pulled a Learn queue that was stale since the last boot
// or the 3:30am nightly cron, discarding whatever it had already triaged locally since. It
// also never drained the Drive mailbox first, so even a rebuilt bundle could miss grades/
// files/discards the phone had JUST pushed moments earlier. Both are fixed here.
@ExtendWith(MockitoExtension.class)
class PwaControllerTest {

    @Mock SettingsRepository settings;
    @Mock DeviceIdentityService deviceIdentity;
    @Mock OfflineExportService offlineExport;
    @Mock MailboxConsumeService mailboxConsume;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
            new PwaController(settings, deviceIdentity, offlineExport, mailboxConsume)).build();
    }

    @Test
    void export_drainsMailboxBeforeRebuildingAllThreeBundles() throws Exception {
        when(offlineExport.exportReviewBundle(200)).thenReturn(12);
        when(offlineExport.exportCards(50)).thenReturn(3);
        when(offlineExport.exportInbox()).thenReturn(5);

        mvc.perform(post("/pwa/export"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notes").value(12))
            .andExpect(jsonPath("$.assignments").value(3))
            .andExpect(jsonPath("$.inbox").value(5));

        InOrder order = inOrder(mailboxConsume, offlineExport);
        order.verify(mailboxConsume).consumeAll();
        order.verify(offlineExport).exportReviewBundle(200);
        order.verify(offlineExport).exportCards(50);
        order.verify(offlineExport).exportInbox();
    }

    @Test
    void export_mailboxDrainFailure_stillRebuildsFromCurrentServerState() throws Exception {
        doThrow(new RuntimeException("Drive unreachable")).when(mailboxConsume).consumeAll();
        when(offlineExport.exportReviewBundle(200)).thenReturn(1);
        when(offlineExport.exportCards(50)).thenReturn(0);
        when(offlineExport.exportInbox()).thenReturn(2);

        mvc.perform(post("/pwa/export"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inbox").value(2));
    }

    @Test
    void export_exportFailure_returns500() throws Exception {
        when(offlineExport.exportReviewBundle(200)).thenThrow(new RuntimeException("disk full"));

        mvc.perform(post("/pwa/export")).andExpect(status().isInternalServerError());
    }

    @Test
    void export_notConfigured_returns409() throws Exception {
        when(offlineExport.exportReviewBundle(200)).thenThrow(new IllegalStateException("Drive not connected"));

        mvc.perform(post("/pwa/export")).andExpect(status().isConflict());
    }
}
