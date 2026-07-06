package com.obsidian.obsidian.pwa;

import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.DeviceIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * One-time install handshake for the installed PWA. The phone calls this ONCE over the
 * tunnel (session-authed, like every other endpoint — SecurityConfig anyRequest()
 * .authenticated()) and stores the returned credentials in IndexedDB. Thereafter the
 * phone reads/writes Google Drive DIRECTLY (same OAuth client → same drive.file
 * namespace) and never needs the server again. See DRIVE_OFFLINE_SYNC_ARCH §5.
 *
 * Paths: nginx strips {@code /api/}, so this maps {@code /pwa/**} → browser {@code /api/pwa/**}.
 *
 * SECURITY: this returns the OAuth client secret, refresh token, and vault passphrase in
 * clear. That is the whole point (the phone must decrypt the vault and refresh its own
 * token) and it is the same trust level as the plaintext vault on the laptop — but it is
 * why the endpoint is session-gated and only ever called on a device you control.
 */
@RestController
@RequestMapping("/pwa")
public class PwaController {

    private final SettingsRepository settings;
    private final DeviceIdentityService deviceIdentity;
    private final OfflineExportService offlineExport;

    public PwaController(SettingsRepository settings, DeviceIdentityService deviceIdentity,
                         OfflineExportService offlineExport) {
        this.settings = settings;
        this.deviceIdentity = deviceIdentity;
        this.offlineExport = offlineExport;
    }

    public record PwaSetup(
        String clientId,
        String clientSecret,
        String refreshToken,
        String driveFolderId,
        String passphrase,
        String deviceId
    ) {}

    /**
     * Hand the phone everything it needs to go server-independent. 409 if the server
     * isn't ready (Drive not connected or passphrase unset) — the UI then tells the user
     * to finish Drive setup on the desktop first.
     */
    @GetMapping("/setup")
    public ResponseEntity<?> setup() {
        String clientId     = settings.getSyncClientId();
        String clientSecret = settings.getSyncClientSecret();
        String refreshToken = settings.getSyncRefreshToken();
        String passphrase   = settings.getSyncPassphrase();
        String folderId     = settings.getSyncDriveFolderId();

        if (isBlank(clientId) || isBlank(clientSecret) || isBlank(refreshToken)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("Drive is not connected on the server — connect Google Drive in Settings first.");
        }
        if (isBlank(passphrase)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("Sync passphrase is not set on the server — set it in Settings first.");
        }

        return ResponseEntity.ok(new PwaSetup(
            clientId, clientSecret, refreshToken, folderId, passphrase, deviceIdentity.getDeviceId()));
    }

    /** "Prep offline set" — rebuild the encrypted review bundle on Drive now. Runs while
     *  the server is up (nightly + on-boot cover the rest). */
    @PostMapping("export")
    public ResponseEntity<?> export() {
        try {
            int n = offlineExport.exportReviewBundle(200);
            return ResponseEntity.ok(Map.of("exported", n));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}

