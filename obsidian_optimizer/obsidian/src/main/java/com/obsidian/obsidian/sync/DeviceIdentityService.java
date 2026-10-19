package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HexFormat;

@Service
public class DeviceIdentityService {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityService.class);
    private static final String SETTINGS_KEY = "sync.device_id";

    private final SettingsRepository settingsRepo;

    public DeviceIdentityService(SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    /**
     * Returns a stable 16-char device ID for {@code vaultId}. Vault 1 keeps the MAC-derived
     * id (migrated from the old global setting); every other vault derives its own from
     * MAC + vault id so each Drive folder sees a distinct device. Persisted in vault_settings.
     */
    public String getDeviceId(long vaultId) {
        String stored = settingsRepo.getForVault(vaultId, SETTINGS_KEY, "");
        if (!stored.isBlank()) return stored;

        String base = computeDeviceId();
        String id = vaultId == 1 ? base : ContentHashing.sha256(base + "#" + vaultId).substring(0, 16);
        settingsRepo.setForVault(vaultId, SETTINGS_KEY, id);
        log.info("[DeviceIdentityService] assigned device_id={} for vault {}", id, vaultId);
        return id;
    }

    private String computeDeviceId() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    return ContentHashing.sha256(HexFormat.of().formatHex(mac)).substring(0, 16);
                }
            }
        } catch (Exception e) {
            log.warn("[DeviceIdentityService] MAC lookup failed, falling back to hostname: {}", e.getMessage());
        }
        try {
            return ContentHashing.sha256(InetAddress.getLocalHost().getHostName()).substring(0, 16);
        } catch (Exception e) {
            return ContentHashing.sha256("unknown-device").substring(0, 16);
        }
    }
}
