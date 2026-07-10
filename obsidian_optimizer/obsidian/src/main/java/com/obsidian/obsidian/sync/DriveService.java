package com.obsidian.obsidian.sync;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DriveService {

    private static final Logger log = LoggerFactory.getLogger(DriveService.class);
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    /** Name of the auto-created sync root in the user's Drive (OAuth mode only). */
    private static final String DEFAULT_ROOT_NAME = "ObsidianOptimizer";

    /** Sub-folder under the sync root holding encrypted DB dumps. Deliberately NOT a
     *  vault path: excluded from listRecursive so the janitor never trashes it and
     *  downloadAll never treats a dump as a note. */
    private static final String DB_BACKUP_FOLDER = "_db";

    /** Sub-folders for the PWA offline sync (DRIVE_OFFLINE_SYNC_ARCH). Like _db/, these are
     *  NOT vault paths — excluded from listRecursive so the janitor/downloadAll ignore them.
     *  _offline/ = server→phone exports (review bundle, cards); _mailbox/ = phone→server events. */
    private static final String OFFLINE_FOLDER = "_offline";
    private static final String MAILBOX_FOLDER = "_mailbox";

    @Value("${sync.google.service-account-json:placeholder}")
    private String serviceAccountJson;

    /** Max attempts for a single Drive write before giving up (transient errors only). */
    @Value("${sync.upload.max-retries:5}")
    private int maxRetries;

    private final SettingsRepository settingsRepo;

    // Built lazily so credentials added via the Settings UI take effect without a
    // restart. reset() drops it after connect/disconnect/credential edits.
    private volatile Drive drive;
    private volatile String activeMode = "none"; // oauth | service-account | none

    /** Cache: "parentFolderId/name" → child folder ID. Avoids redundant list API calls. */
    private final Map<String, String> folderCache = new ConcurrentHashMap<>();

    /** Per-folder-key locks so concurrent uploads never double-create the same folder. */
    private final Map<String, Object> folderLocks = new ConcurrentHashMap<>();

    public DriveService(SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    /**
     * Credential priority: OAuth refresh token from settings (user signed in via the
     * Settings page — files land in THEIR Drive/quota) → service-account env JSON
     * (headless fallback, 15GB service-account quota) → null (sync disabled).
     */
    private synchronized Drive ensureClient() {
        if (drive != null) return drive;
        try {
            String refreshToken = settingsRepo.getSyncRefreshToken();
            String clientId     = settingsRepo.getSyncClientId();
            String clientSecret = settingsRepo.getSyncClientSecret();

            HttpCredentialsAdapter adapter;
            if (!refreshToken.isBlank() && !clientId.isBlank() && !clientSecret.isBlank()) {
                UserCredentials creds = UserCredentials.newBuilder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRefreshToken(refreshToken)
                    .build();
                adapter = new HttpCredentialsAdapter(creds);
                activeMode = "oauth";
            } else if (!serviceAccountJson.isBlank() && !"placeholder".equals(serviceAccountJson)) {
                GoogleCredentials creds = GoogleCredentials
                    .fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)))
                    .createScoped(Collections.singleton(DriveScopes.DRIVE));
                adapter = new HttpCredentialsAdapter(creds);
                activeMode = "service-account";
            } else {
                activeMode = "none";
                return null;
            }

            drive = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                adapter)
                .setApplicationName("ObsidianOptimizer")
                .build();
            log.info("[DriveService] Google Drive client initialised ({})", activeMode);
            return drive;
        } catch (Exception e) {
            log.error("[DriveService] client init failed: {}", e.getMessage());
            activeMode = "none";
            return null;
        }
    }

    /** Drop the client + caches so the next call rebuilds from current settings. */
    public synchronized void reset() {
        drive = null;
        activeMode = "none";
        folderCache.clear();
        folderLocks.clear();
    }

    /** oauth | service-account | none — which credential source WOULD be used. */
    public String mode() {
        String refreshToken = settingsRepo.getSyncRefreshToken();
        if (!refreshToken.isBlank()
            && !settingsRepo.getSyncClientId().isBlank()
            && !settingsRepo.getSyncClientSecret().isBlank()) return "oauth";
        if (!serviceAccountJson.isBlank() && !"placeholder".equals(serviceAccountJson)) return "service-account";
        return "none";
    }

    public boolean isConfigured() {
        String m = mode();
        if ("oauth".equals(m)) return true;                     // root folder is auto-created
        return "service-account".equals(m) && !settingsRepo.getSyncDriveFolderId().isBlank();
    }

    /**
     * Root folder for the encrypted mirror. Explicit setting/env wins; in OAuth mode a
     * top-level "ObsidianOptimizer" folder is found-or-created (drive.file scope only
     * sees app-created files, so the by-name lookup cannot collide with user files)
     * and persisted so every device reuses the same ID.
     */
    private String rootFolderId() throws IOException {
        String configured = settingsRepo.getSyncDriveFolderId();
        if (!configured.isBlank()) return configured;
        if (!"oauth".equals(mode())) throw new IOException("Drive root folder not configured");

        Drive d = requireClient();
        FileList found = d.files().list()
            .setQ("name = '" + DEFAULT_ROOT_NAME + "' and mimeType = '" + FOLDER_MIME + "' and trashed = false")
            .setFields("files(id)").setPageSize(1).execute();
        String id;
        if (!found.getFiles().isEmpty()) {
            id = found.getFiles().get(0).getId();
        } else {
            File folder = new File().setName(DEFAULT_ROOT_NAME).setMimeType(FOLDER_MIME);
            id = d.files().create(folder).setFields("id").execute().getId();
            log.info("[DriveService] created sync root folder '{}' ({})", DEFAULT_ROOT_NAME, id);
        }
        settingsRepo.set("sync.drive.folder_id", id);
        return id;
    }

    private Drive requireClient() throws IOException {
        Drive d = ensureClient();
        if (d == null) throw new IOException("Google Drive not configured");
        return d;
    }

    /** Email of the connected account (OAuth) — used for the Settings status line. */
    public String fetchAccountEmail() throws IOException {
        return requireClient().about().get().setFields("user(emailAddress)")
            .execute().getUser().getEmailAddress();
    }

    /** Drive storage quota {usedBytes, limitBytes} — limitBytes null = unlimited. */
    public Map<String, Long> fetchQuota() throws IOException {
        var q = requireClient().about().get().setFields("storageQuota").execute().getStorageQuota();
        Map<String, Long> out = new HashMap<>();
        out.put("usedBytes",  q.getUsage());
        out.put("limitBytes", q.getLimit());
        return out;
    }

    /**
     * Move a Drive file to TRASH (30-day recovery), never a hard delete —
     * {@code files().delete()} would bypass the trash entirely.
     */
    public void trashFile(String fileId) throws IOException {
        try {
            requireClient().files().update(fileId, new File().setTrashed(true))
                .setFields("id").execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() != 404) throw e;   // already gone = done
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Upload or update a file at {@code relativePath} (e.g. "notes/folder/note.md").
     * The Drive file is stored as {@code relativePath + ".enc"} under the sync root folder.
     * Returns the Drive file ID.
     */
    public String uploadFile(String relativePath, byte[] bytes,
                             String contentHash, String deviceId,
                             String existingFileId) throws IOException {
        String encName  = leafName(relativePath) + ".enc";
        String folderId = ensureFolderPath(relativePath);

        Map<String, String> props = new HashMap<>();
        props.put("vault_path",   relativePath);
        props.put("content_hash", contentHash);
        props.put("device_id",    deviceId);
        props.put("uploaded_at",  String.valueOf(System.currentTimeMillis()));

        ByteArrayContent content = new ByteArrayContent("application/octet-stream", bytes);
        Drive d = requireClient();

        if (existingFileId != null) {
            try {
                File meta = new File().setName(encName).setAppProperties(props);
                withRetry(() -> d.files().update(existingFileId, meta, content).setFields("id").execute());
                return existingFileId;
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() != 404) throw e;
                // Drive file was deleted — fall through to create
            }
        }

        File meta = new File()
            .setName(encName)
            .setParents(Collections.singletonList(folderId))
            .setAppProperties(props);
        return withRetry(() -> d.files().create(meta, content).setFields("id").execute()).getId();
    }

    /**
     * Run a single Drive write with exponential backoff + jitter on transient failures
     * (403 rateLimitExceeded/userRateLimitExceeded, 429, 5xx). Non-transient errors
     * (404, auth, quota-exceeded storage, etc.) rethrow immediately. Needed because the
     * uploader runs many concurrent writes and Drive will rate-limit bursts.
     */
    private <T> T withRetry(DriveCall<T> call) throws IOException {
        int attempt = 0;
        while (true) {
            try {
                return call.run();
            } catch (GoogleJsonResponseException e) {
                String reason = reasonOf(e);
                if (!isTransient(e.getStatusCode(), reason) || ++attempt >= maxRetries) {
                    if (e.getStatusCode() == 403 || e.getStatusCode() == 429) {
                        log.warn("[DriveService] giving up after {} attempt(s): {} reason='{}'",
                            attempt, e.getStatusCode(), reason);
                    }
                    throw e;
                }
                sleepBackoff(attempt);
            } catch (java.net.SocketTimeoutException | java.net.UnknownHostException e) {
                if (++attempt >= maxRetries) throw e;
                sleepBackoff(attempt);
            }
        }
    }

    private static String reasonOf(GoogleJsonResponseException e) {
        if (e.getDetails() != null && e.getDetails().getErrors() != null
                && !e.getDetails().getErrors().isEmpty()) {
            return String.valueOf(e.getDetails().getErrors().get(0).getReason());
        }
        return "";
    }

    /**
     * Which Drive write failures are worth retrying: 429 (too many requests), any 5xx,
     * and 403 UNLESS it carries a known-permanent reason. Drive rate-limits a concurrent
     * burst with a 403 whose reason is often empty/unparsed ("403 Forbidden"), so 403 is
     * treated as retryable by default and only the genuinely permanent reasons (bad
     * permissions, storage full, disabled app) are excluded. Everything else (404, 401,
     * 400) is permanent. Pure so it can be unit-tested without a live Drive exception.
     */
    static boolean isTransient(int statusCode, String reason) {
        if (statusCode == 429 || statusCode >= 500) return true;
        if (statusCode == 403) {
            if (reason == null || reason.isBlank()) return true;   // unlabelled burst 403 → back off
            return !(reason.contains("insufficientPermissions")
                  || reason.contains("insufficientFilePermissions")
                  || reason.contains("storageQuotaExceeded")
                  || reason.contains("appNotAuthorizedToFile")
                  || reason.contains("domainPolicy")
                  || reason.contains("sharingRateLimitExceeded"));
        }
        return false;
    }

    private static void sleepBackoff(int attempt) {
        // 0.5s, 1s, 2s, 4s … + up to 250ms jitter, capped at 8s.
        long base = Math.min(8000L, 500L * (1L << (attempt - 1)));
        long delay = base + (long) (Math.random() * 250);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface DriveCall<T> {
        T run() throws IOException;
    }

    // ── Download ──────────────────────────────────────────────────────────────

    public byte[] downloadFile(String fileId) throws IOException {
        // Same transient-retry policy as uploads: a 429/5xx/burst-403 during a bulk pull
        // (e.g. a restore of thousands of files) no longer strands that file permanently.
        return withRetry(() -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            requireClient().files().get(fileId).executeMediaAndDownloadTo(bos);
            return bos.toByteArray();
        });
    }

    // ── List ──────────────────────────────────────────────────────────────────

    /** Recursively list all .enc files under the sync root, returning their metadata. */
    public List<DriveFileInfo> listAllFiles() throws IOException {
        List<DriveFileInfo> result = new ArrayList<>();
        listRecursive(rootFolderId(), result);
        return result;
    }

    private void listRecursive(String folderId, List<DriveFileInfo> acc) throws IOException {
        String pageToken = null;
        do {
            Drive.Files.List req = requireClient().files().list()
                .setQ("'" + folderId + "' in parents and trashed = false")
                .setFields("nextPageToken, files(id, name, mimeType, size, appProperties)")
                .setPageSize(1000);
            if (pageToken != null) req.setPageToken(pageToken);
            FileList page = req.execute();

            for (File f : page.getFiles()) {
                if (FOLDER_MIME.equals(f.getMimeType())) {
                    // Never descend into _db/ — those are DB dumps, not vault files.
                    // (Keeps the janitor from trashing backups and downloadAll from
                    //  trying to decrypt a pg_dump as a note.)
                    if (DB_BACKUP_FOLDER.equals(f.getName())
                        || OFFLINE_FOLDER.equals(f.getName())
                        || MAILBOX_FOLDER.equals(f.getName())) continue;
                    listRecursive(f.getId(), acc);
                } else if (f.getName().endsWith(".enc")) {
                    Map<String, String> props = f.getAppProperties();
                    if (props != null && props.containsKey("vault_path")) {
                        acc.add(new DriveFileInfo(
                            f.getId(),
                            props.get("vault_path"),
                            props.getOrDefault("content_hash", ""),
                            props.getOrDefault("device_id", ""),
                            parseLong(props.getOrDefault("uploaded_at", "0")),
                            f.getSize() == null ? 0L : f.getSize()
                        ));
                    }
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
    }

    // ── DB backups (_db/) ───────────────────────────────────────────────────────

    /** Upload an encrypted pg_dump into the _db/ folder. Returns the Drive file ID. */
    public String uploadDbBackup(byte[] bytes, String name, String pgVersion, String deviceId)
            throws IOException {
        String folderId = getOrCreateFolder(DB_BACKUP_FOLDER, rootFolderId());
        Map<String, String> props = new HashMap<>();
        props.put("type",        "db-dump");
        props.put("pg_version",  pgVersion == null ? "" : pgVersion);
        props.put("created_at",  String.valueOf(System.currentTimeMillis()));
        props.put("device_id",   deviceId == null ? "" : deviceId);
        File meta = new File()
            .setName(name)
            .setParents(Collections.singletonList(folderId))
            .setAppProperties(props);
        ByteArrayContent content = new ByteArrayContent("application/octet-stream", bytes);
        Drive d = requireClient();
        return withRetry(() -> d.files().create(meta, content).setFields("id").execute()).getId();
    }

    /** All DB dumps in _db/, newest first (by created_at appProperty, then name). */
    public List<DbBackupInfo> listDbBackups() throws IOException {
        List<DbBackupInfo> out = new ArrayList<>();
        String folderId = getOrCreateFolder(DB_BACKUP_FOLDER, rootFolderId());
        String pageToken = null;
        do {
            Drive.Files.List req = requireClient().files().list()
                .setQ("'" + folderId + "' in parents and trashed = false")
                .setFields("nextPageToken, files(id, name, size, appProperties)")
                .setPageSize(1000);
            if (pageToken != null) req.setPageToken(pageToken);
            FileList page = req.execute();
            for (File f : page.getFiles()) {
                if (!f.getName().endsWith(".enc")) continue;
                Map<String, String> props = f.getAppProperties();
                long created = props != null ? parseLong(props.getOrDefault("created_at", "0")) : 0L;
                out.add(new DbBackupInfo(f.getId(), f.getName(), created,
                    f.getSize() == null ? 0L : f.getSize()));
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        out.sort((a, b) -> {
            int c = Long.compare(b.createdAt(), a.createdAt());
            return c != 0 ? c : b.name().compareTo(a.name());
        });
        return out;
    }

    public java.util.Optional<DbBackupInfo> latestDbBackup() throws IOException {
        List<DbBackupInfo> all = listDbBackups();
        return all.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(all.get(0));
    }

    /** Hard-delete an old dump (rotation) — bypasses trash so retention actually frees quota. */
    public void deleteDbBackup(String fileId) throws IOException {
        try {
            requireClient().files().delete(fileId).execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() != 404) throw e;
        }
    }

    public record DbBackupInfo(String fileId, String name, long createdAt, long sizeBytes) {}

    // ── Folder helpers ────────────────────────────────────────────────────────

    /**
     * Ensure all intermediate folders for {@code relativePath} exist under the
     * sync root and return the ID of the immediate parent folder.
     * e.g. "notes/folder/note.md" → ensures notes/ and notes/folder/ exist.
     */
    /**
     * Ensures all intermediate folders exist and returns the immediate parent's ID.
     */
    private String ensureFolderPath(String relativePath) throws IOException {
        String[] parts = relativePath.split("/");
        String current = rootFolderId();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isBlank()) {
                current = getOrCreateFolder(parts[i], current);
            }
        }
        return current;
    }

    /**
     * Find-or-create a child folder. Thread-safe: the concurrent uploader hits this from
     * many threads, so the find-or-create is serialized per (parent/name) key — otherwise
     * two threads both see "missing" and each creates a DUPLICATE Drive folder. A cache
     * hit (the common case) is lock-free; only a first miss for a given folder blocks, and
     * only other threads wanting that same folder.
     */
    private String getOrCreateFolder(String name, String parentId) throws IOException {
        String cacheKey = parentId + "/" + name;
        String cached = folderCache.get(cacheKey);
        if (cached != null) return cached;

        Object lock = folderLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            // Re-check: another thread may have created it while we waited.
            String again = folderCache.get(cacheKey);
            if (again != null) return again;

            Drive d = requireClient();
            String q = "name = '" + name.replace("'", "\\'") + "'"
                + " and '" + parentId + "' in parents"
                + " and mimeType = '" + FOLDER_MIME + "'"
                + " and trashed = false";
            FileList result = d.files().list()
                .setQ(q).setFields("files(id)").setPageSize(1).execute();

            String id;
            if (!result.getFiles().isEmpty()) {
                id = result.getFiles().get(0).getId();
            } else {
                File folder = new File()
                    .setName(name)
                    .setMimeType(FOLDER_MIME)
                    .setParents(Collections.singletonList(parentId));
                id = d.files().create(folder).setFields("id").execute().getId();
            }
            folderCache.put(cacheKey, id);
            return id;
        }
    }

    // ── PWA offline sync (_offline/ exports, _mailbox/ events) ──────────────────

    /** Upsert a singleton file into _offline/ (overwrite so exports don't accumulate). */
    public String uploadOffline(String name, byte[] bytes) throws IOException {
        String folderId = getOrCreateFolder(OFFLINE_FOLDER, rootFolderId());
        String existingId = findInFolder(folderId, name);
        ByteArrayContent content = new ByteArrayContent("application/octet-stream", bytes);
        Drive d = requireClient();
        if (existingId != null) {
            return withRetry(() -> d.files().update(existingId, new File(), content).setFields("id").execute()).getId();
        }
        File meta = new File().setName(name).setParents(Collections.singletonList(folderId));
        return withRetry(() -> d.files().create(meta, content).setFields("id").execute()).getId();
    }

    /** List encrypted event files the phone dropped in _mailbox/, oldest name first. */
    public List<MailboxFile> listMailbox() throws IOException {
        String folderId = getOrCreateFolder(MAILBOX_FOLDER, rootFolderId());
        List<MailboxFile> out = new ArrayList<>();
        String pageToken = null;
        do {
            Drive.Files.List req = requireClient().files().list()
                .setQ("'" + folderId + "' in parents and trashed = false")
                .setFields("nextPageToken, files(id, name)")
                .setPageSize(1000);
            if (pageToken != null) req.setPageToken(pageToken);
            FileList page = req.execute();
            for (File f : page.getFiles()) {
                if (f.getName().endsWith(".enc")) out.add(new MailboxFile(f.getId(), f.getName()));
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        out.sort(Comparator.comparing(MailboxFile::name)); // names are <device>-<ts>-<seq> → ts order
        return out;
    }

    /** HARD delete (not trash) a consumed mailbox file — these are transient, not vault data. */
    public void deleteFile(String fileId) throws IOException {
        try {
            requireClient().files().delete(fileId).execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() != 404) throw e; // already gone = done
        }
    }

    private String findInFolder(String folderId, String name) throws IOException {
        FileList result = requireClient().files().list()
            .setQ("name = '" + name.replace("'", "\\'") + "' and '" + folderId
                + "' in parents and trashed = false")
            .setFields("files(id)").setPageSize(1).execute();
        return result.getFiles().isEmpty() ? null : result.getFiles().get(0).getId();
    }

    public record MailboxFile(String fileId, String name) {}

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String leafName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    public record DriveFileInfo(
        String fileId,
        String vaultPath,
        String contentHash,
        String deviceId,
        long uploadedAt,
        long sizeBytes
    ) {}
}
