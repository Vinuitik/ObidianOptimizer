package com.obsidian.obsidian.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Google OAuth for Drive sync — the "Connect Google Drive" button in Settings.
 *
 * The user creates an OAuth client (Web application) once in Google Cloud Console and
 * pastes its id/secret into Settings; this service runs the authorization-code flow
 * and stores the refresh token in app_settings. Scope is {@code drive.file} (only
 * files this app creates — the app never sees the rest of the user's Drive), and the
 * synced files are owned by the USER's account, so quota/visibility problems of the
 * service-account fallback disappear.
 *
 * Single-user app ⇒ one in-flight consent at a time (the {@code pending} field).
 */
@Service
public class SyncOAuthService {

    private static final Logger log = LoggerFactory.getLogger(SyncOAuthService.class);

    private static final String AUTH_ENDPOINT   = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT  = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";
    private static final String SCOPE           = "https://www.googleapis.com/auth/drive.file";
    private static final String CALLBACK_PATH   = "/api/sync/oauth/callback";
    private static final long   STATE_TTL_MS    = 10 * 60 * 1000;

    // http only for localhost dev; anything else must be https (the tunnel domain).
    private static final Pattern ORIGIN_OK =
        Pattern.compile("^(https://[^/\\s]+|http://localhost(:\\d+)?|http://127\\.0\\.0\\.1(:\\d+)?)$");

    private record Pending(String state, String redirectUri, long expiresAt) {}

    private final SettingsRepository settingsRepo;
    private final DriveService driveService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private volatile Pending pending;

    public SyncOAuthService(SettingsRepository settingsRepo, DriveService driveService) {
        this.settingsRepo = settingsRepo;
        this.driveService = driveService;
    }

    public boolean isClientConfigured() {
        return !settingsRepo.getSyncClientId().isBlank()
            && !settingsRepo.getSyncClientSecret().isBlank();
    }

    public boolean isConnected() {
        return !settingsRepo.getSyncRefreshToken().isBlank();
    }

    /**
     * Build the Google consent URL. {@code origin} is the frontend's
     * {@code window.location.origin}; the derived redirect URI must be registered on
     * the OAuth client in Google Cloud Console.
     */
    public String buildAuthUrl(String origin) {
        if (!isClientConfigured()) {
            throw new IllegalStateException("Save the OAuth client id and secret first");
        }
        if (origin == null || !ORIGIN_OK.matcher(origin.trim()).matches()) {
            throw new IllegalArgumentException("invalid origin");
        }
        String redirectUri = origin.trim() + CALLBACK_PATH;
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String state = HexFormat.of().formatHex(nonce);
        pending = new Pending(state, redirectUri, System.currentTimeMillis() + STATE_TTL_MS);

        // access_type=offline + prompt=consent → Google always returns a refresh token.
        return AUTH_ENDPOINT
            + "?client_id="     + url(settingsRepo.getSyncClientId())
            + "&redirect_uri="  + url(redirectUri)
            + "&response_type=code"
            + "&scope="         + url(SCOPE)
            + "&access_type=offline"
            + "&prompt=consent"
            + "&state="         + state;
    }

    /**
     * Exchange the authorization code, store the refresh token, and record the
     * connected account's email. Returns the email (best-effort, may be "").
     */
    public String handleCallback(String code, String state) throws IOException {
        Pending p = pending;
        pending = null; // single-use
        if (p == null || !p.state().equals(state) || System.currentTimeMillis() > p.expiresAt()) {
            throw new IOException("OAuth state mismatch or expired — restart the connect flow");
        }

        String form = "code="          + url(code)
            + "&client_id="     + url(settingsRepo.getSyncClientId())
            + "&client_secret=" + url(settingsRepo.getSyncClientSecret())
            + "&redirect_uri="  + url(p.redirectUri())
            + "&grant_type=authorization_code";

        JsonNode tokens = postForm(TOKEN_ENDPOINT, form);
        String refreshToken = tokens.path("refresh_token").asText("");
        if (refreshToken.isBlank()) {
            throw new IOException("Google returned no refresh token: " + tokens.path("error_description").asText(tokens.toString()));
        }

        settingsRepo.set("sync.refresh_token", refreshToken);
        driveService.reset();

        String email = "";
        try {
            email = driveService.fetchAccountEmail();
        } catch (Exception e) {
            log.warn("[SyncOAuth] connected but could not read account email: {}", e.getMessage());
        }
        settingsRepo.set("sync.account_email", email);
        log.info("[SyncOAuth] Google Drive connected{}", email.isBlank() ? "" : " as " + email);
        return email;
    }

    /** Revoke (best-effort) and forget the stored token. */
    public void disconnect() {
        String token = settingsRepo.getSyncRefreshToken();
        if (!token.isBlank()) {
            try {
                postForm(REVOKE_ENDPOINT, "token=" + url(token));
            } catch (Exception e) {
                log.warn("[SyncOAuth] revoke failed (token forgotten anyway): {}", e.getMessage());
            }
        }
        settingsRepo.set("sync.refresh_token", "");
        settingsRepo.set("sync.account_email", "");
        driveService.reset();
        log.info("[SyncOAuth] Google Drive disconnected");
    }

    private JsonNode postForm(String endpoint, String form) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        try {
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = r.body() == null || r.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(r.body());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("Google " + r.statusCode() + ": "
                    + body.path("error_description").asText(body.path("error").asText(r.body())));
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String url(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** For the status endpoint. */
    public Map<String, Object> statusFragment() {
        return Map.of(
            "mode",             driveService.mode(),
            "clientConfigured", isClientConfigured(),
            "connected",        isConnected(),
            "accountEmail",     settingsRepo.getSyncAccountEmail()
        );
    }
}
