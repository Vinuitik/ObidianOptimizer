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
import jakarta.annotation.PostConstruct;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DriveService {

    private static final Logger log = LoggerFactory.getLogger(DriveService.class);
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    @Value("${sync.google.service-account-json:placeholder}")
    private String serviceAccountJson;

    @Value("${sync.google.drive.folder-id:placeholder}")
    private String rootFolderId;

    private Drive drive;

    /** Cache: "parentFolderId/name" → child folder ID. Avoids redundant list API calls. */
    private final Map<String, String> folderCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (serviceAccountJson.isBlank() || "placeholder".equals(serviceAccountJson)) {
            log.warn("[DriveService] No service account configured — Drive sync disabled");
            return;
        }
        try {
            GoogleCredentials creds = GoogleCredentials
                .fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)))
                .createScoped(Collections.singleton(DriveScopes.DRIVE));
            drive = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(creds))
                .setApplicationName("ObsidianOptimizer")
                .build();
            log.info("[DriveService] Google Drive client initialised");
        } catch (Exception e) {
            log.error("[DriveService] init failed: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return drive != null && !rootFolderId.isBlank() && !"placeholder".equals(rootFolderId);
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

        if (existingFileId != null) {
            try {
                File meta = new File().setName(encName).setAppProperties(props);
                drive.files().update(existingFileId, meta, content).setFields("id").execute();
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
        return drive.files().create(meta, content).setFields("id").execute().getId();
    }

    // ── Download ──────────────────────────────────────────────────────────────

    public byte[] downloadFile(String fileId) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        drive.files().get(fileId).executeMediaAndDownloadTo(bos);
        return bos.toByteArray();
    }

    // ── List ──────────────────────────────────────────────────────────────────

    /** Recursively list all .enc files under the sync root, returning their metadata. */
    public List<DriveFileInfo> listAllFiles() throws IOException {
        List<DriveFileInfo> result = new ArrayList<>();
        listRecursive(rootFolderId, result);
        return result;
    }

    private void listRecursive(String folderId, List<DriveFileInfo> acc) throws IOException {
        String pageToken = null;
        do {
            Drive.Files.List req = drive.files().list()
                .setQ("'" + folderId + "' in parents and trashed = false")
                .setFields("nextPageToken, files(id, name, mimeType, appProperties)")
                .setPageSize(1000);
            if (pageToken != null) req.setPageToken(pageToken);
            FileList page = req.execute();

            for (File f : page.getFiles()) {
                if (FOLDER_MIME.equals(f.getMimeType())) {
                    listRecursive(f.getId(), acc);
                } else if (f.getName().endsWith(".enc")) {
                    Map<String, String> props = f.getAppProperties();
                    if (props != null && props.containsKey("vault_path")) {
                        acc.add(new DriveFileInfo(
                            f.getId(),
                            props.get("vault_path"),
                            props.getOrDefault("content_hash", ""),
                            props.getOrDefault("device_id", ""),
                            parseLong(props.getOrDefault("uploaded_at", "0"))
                        ));
                    }
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
    }

    // ── Folder helpers ────────────────────────────────────────────────────────

    /**
     * Ensure all intermediate folders for {@code relativePath} exist under the
     * sync root and return the ID of the immediate parent folder.
     * e.g. "notes/folder/note.md" → ensures notes/ and notes/folder/ exist.
     */
    private String ensureFolderPath(String relativePath) throws IOException {
        String[] parts = relativePath.split("/");
        String current = rootFolderId;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isBlank()) {
                current = getOrCreateFolder(parts[i], current);
            }
        }
        return current;
    }

    private String getOrCreateFolder(String name, String parentId) throws IOException {
        String cacheKey = parentId + "/" + name;
        String cached = folderCache.get(cacheKey);
        if (cached != null) return cached;

        String q = "name = '" + name.replace("'", "\\'") + "'"
            + " and '" + parentId + "' in parents"
            + " and mimeType = '" + FOLDER_MIME + "'"
            + " and trashed = false";
        FileList result = drive.files().list()
            .setQ(q).setFields("files(id)").setPageSize(1).execute();

        String id;
        if (!result.getFiles().isEmpty()) {
            id = result.getFiles().get(0).getId();
        } else {
            File folder = new File()
                .setName(name)
                .setMimeType(FOLDER_MIME)
                .setParents(Collections.singletonList(parentId));
            id = drive.files().create(folder).setFields("id").execute().getId();
        }
        folderCache.put(cacheKey, id);
        return id;
    }

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
        long uploadedAt
    ) {}
}
