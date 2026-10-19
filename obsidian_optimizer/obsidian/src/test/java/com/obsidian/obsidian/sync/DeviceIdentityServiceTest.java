package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.settings.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Device IDs tag every uploaded file's appProperties for future per-file conflict
 * resolution — getting this non-deterministic or unstable across restarts would make
 * that metadata worthless.
 */
class DeviceIdentityServiceTest {

    SettingsRepository settingsRepo;
    DeviceIdentityService service;

    @BeforeEach
    void setUp() {
        settingsRepo = mock(SettingsRepository.class);
        service = new DeviceIdentityService(settingsRepo);
    }

    @Test
    void getDeviceId_returnsStoredValue_withoutRecomputing() {
        when(settingsRepo.getForVault(1L, "sync.device_id", "")).thenReturn("already-assigned-id");

        String id = service.getDeviceId(1L);

        assertThat(id).isEqualTo("already-assigned-id");
        verify(settingsRepo, never()).setForVault(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
    }

    @Test
    void getDeviceId_firstCall_computesAndPersistsExactlyOnce() {
        when(settingsRepo.getForVault(1L, "sync.device_id", "")).thenReturn("");

        String id = service.getDeviceId(1L);

        assertThat(id).isNotBlank();
        assertThat(id).hasSize(16); // sha256(...).substring(0, 16)
        verify(settingsRepo).setForVault(eq(1L), eq("sync.device_id"), eq(id));
    }

    @Test
    void getDeviceId_isDeterministic_sameMachineProducesSameIdAcrossInstances() {
        when(settingsRepo.getForVault(1L, "sync.device_id", "")).thenReturn("");
        String first = service.getDeviceId(1L);

        SettingsRepository secondRepo = mock(SettingsRepository.class);
        when(secondRepo.getForVault(1L, "sync.device_id", "")).thenReturn("");
        String second = new DeviceIdentityService(secondRepo).getDeviceId(1L);

        // Same host → same MAC/hostname fallback chain → same derived id. If this
        // ever used something per-process-random (e.g. a fresh UUID), the two
        // instances would diverge.
        assertThat(second).isEqualTo(first);
    }

    @Test
    void getDeviceId_blankStoredValue_treatedAsUnassigned_recomputes() {
        // SettingsRepository.getOrDefault can return "" for a key that exists but was
        // never set — must not be treated as "already assigned".
        when(settingsRepo.getForVault(1L, "sync.device_id", "")).thenReturn("   ");

        service.getDeviceId(1L);

        verify(settingsRepo).setForVault(eq(1L), eq("sync.device_id"), anyString());
    }

    @Test
    void getDeviceId_otherVault_getsItsOwnDistinctId() {
        when(settingsRepo.getForVault(1L, "sync.device_id", "")).thenReturn("");
        when(settingsRepo.getForVault(2L, "sync.device_id", "")).thenReturn("");

        String v1 = service.getDeviceId(1L);
        String v2 = service.getDeviceId(2L);

        assertThat(v2).hasSize(16).isNotEqualTo(v1);
        verify(settingsRepo).setForVault(eq(2L), eq("sync.device_id"), eq(v2));
    }
}
