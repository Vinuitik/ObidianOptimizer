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
        when(settingsRepo.getOrDefault("sync.device_id", "")).thenReturn("already-assigned-id");

        String id = service.getDeviceId();

        assertThat(id).isEqualTo("already-assigned-id");
        verify(settingsRepo, never()).set(anyString(), anyString());
    }

    @Test
    void getDeviceId_firstCall_computesAndPersistsExactlyOnce() {
        when(settingsRepo.getOrDefault("sync.device_id", "")).thenReturn("");

        String id = service.getDeviceId();

        assertThat(id).isNotBlank();
        assertThat(id).hasSize(16); // sha256(...).substring(0, 16)
        verify(settingsRepo).set(eq("sync.device_id"), eq(id));
    }

    @Test
    void getDeviceId_isDeterministic_sameMachineProducesSameIdAcrossInstances() {
        when(settingsRepo.getOrDefault("sync.device_id", "")).thenReturn("");
        String first = service.getDeviceId();

        SettingsRepository secondRepo = mock(SettingsRepository.class);
        when(secondRepo.getOrDefault("sync.device_id", "")).thenReturn("");
        String second = new DeviceIdentityService(secondRepo).getDeviceId();

        // Same host → same MAC/hostname fallback chain → same derived id. If this
        // ever used something per-process-random (e.g. a fresh UUID), the two
        // instances would diverge.
        assertThat(second).isEqualTo(first);
    }

    @Test
    void getDeviceId_blankStoredValue_treatedAsUnassigned_recomputes() {
        // SettingsRepository.getOrDefault can return "" for a key that exists but was
        // never set — must not be treated as "already assigned".
        when(settingsRepo.getOrDefault("sync.device_id", "")).thenReturn("   ");

        service.getDeviceId();

        verify(settingsRepo).set(eq("sync.device_id"), anyString());
    }
}
