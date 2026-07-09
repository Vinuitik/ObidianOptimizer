package com.obsidian.obsidian.sync;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import com.google.api.client.json.gson.GsonFactory;

import com.obsidian.obsidian.capture.CaptureIngestWorker;
import com.obsidian.obsidian.chrono.ChronoService;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.pwa.MailboxConsumeService;
import com.obsidian.obsidian.pwa.OfflineExportService;
import com.obsidian.obsidian.settings.SettingsRepository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE Google Drive round-trip — talks to the REAL Drive API with real OAuth creds.
 * Opt-in only: {@code ./linux_scripts/test.sh drive-live} (or set the env vars below).
 * Everything happens inside a dedicated throwaway root folder
 * ({@code ObsidianOptimizer-LIVETEST-<ts>}), so the production mirror is never touched;
 * the folder is hard-deleted at the end.
 *
 * Required env: DRIVE_LIVE=1, DRIVE_LIVE_CLIENT_ID, DRIVE_LIVE_CLIENT_SECRET,
 * DRIVE_LIVE_REFRESH_TOKEN, DRIVE_LIVE_PASSPHRASE.
 *
 * Covers, in order: upload correctness (appProperties, counts), fresh-device restore
 * (byte identity), local-wins conflict rule, tombstone delete propagation, the phone
 * lane (offline export decryptable + mailbox write-back consumed), and prints a
 * sustainability report (throughput, quota) extrapolated to the full vault.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DRIVE_LIVE", matches = "1")
class DriveLiveIT {

    // Singleton container started in a static block (not @Container-managed): the
    // ordered PER_CLASS test methods share one instance and one Postgres, and the
    // explicit start guarantees the mapped port exists before @DynamicPropertySource
    // resolves it (the @Testcontainers extension's beforeAll runs too late under
    // PER_CLASS, hence "Mapped port can only be obtained after the container is started").
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static {
        postgres.start();
    }

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-drive-live-vault");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          VAULT::toString);
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockBean ChronoService chronoService;
    @MockBean CaptureIngestWorker captureIngestWorker;

    @Autowired DriveService           driveService;
    @Autowired SyncService            syncService;
    @Autowired SyncQueueRepository    syncQueue;
    @Autowired VaultEncryptionService encryption;
    @Autowired SettingsRepository     settings;
    @Autowired FileRepository         fileRepo;
    @Autowired NoteIndexRepository    noteIndex;
    @Autowired OfflineExportService   offlineExport;
    @Autowired MailboxConsumeService  mailboxConsume;
    @Autowired JdbcTemplate           jdbc;

    /** Raw Drive client — the test's own door for fixture setup + teardown. */
    private Drive raw;
    private String testRootId;
    private final Map<String, String> uploadedHashes = new HashMap<>(); // rel path → sha256
    private long uploadMillis;
    private int  uploadCount;

    private static final String YESTERDAY = LocalDate.now().minusDays(1).toString();

    // ── setup / teardown ─────────────────────────────────────────────────────

    @BeforeAll
    void connectDrive() throws Exception {
        String clientId  = env("DRIVE_LIVE_CLIENT_ID");
        String secret    = env("DRIVE_LIVE_CLIENT_SECRET");
        String refresh   = env("DRIVE_LIVE_REFRESH_TOKEN");
        String pass      = env("DRIVE_LIVE_PASSPHRASE");

        raw = new Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            new HttpCredentialsAdapter(UserCredentials.newBuilder()
                .setClientId(clientId).setClientSecret(secret)
                .setRefreshToken(refresh).build()))
            .setApplicationName("ObsidianOptimizer-LiveIT").build();

        File folder = new File()
            .setName("ObsidianOptimizer-LIVETEST-" + System.currentTimeMillis())
            .setMimeType("application/vnd.google-apps.folder");
        testRootId = raw.files().create(folder).setFields("id").execute().getId();

        settings.set("syncClientId", clientId);
        settings.set("syncClientSecret", secret);
        settings.set("sync.refresh_token", refresh);
        settings.set("syncPassphrase", pass);
        settings.set("sync.drive.folder_id", testRootId);   // NEVER the prod mirror
        driveService.reset();
        encryption.reload();

        assertThat(driveService.isConfigured()).isTrue();
        assertThat(encryption.isConfigured()).isTrue();
    }

    @AfterAll
    void destroyTestFolder() throws Exception {
        if (raw != null && testRootId != null) {
            raw.files().delete(testRootId).execute();   // hard delete, descendants included
            FileList left = raw.files().list()
                .setQ("'" + testRootId + "' in parents and trashed = false")
                .setFields("files(id)").execute();
            assertThat(left.getFiles()).isEmpty();
        }
        try (var stream = Files.walk(VAULT)) {
            stream.sorted(Comparator.reverseOrder())
                  .filter(p -> !p.equals(VAULT))
                  .forEach(p -> p.toFile().delete());
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        assertThat(v).as("env %s must be set for the live Drive test", name).isNotBlank();
        return v;
    }

    /** Write a raw file (used for resources). */
    private Path write(String rel, byte[] bytes) throws IOException {
        Path f = VAULT.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        return f;
    }

    /** Create a .md note through the REAL FileRepository write path so it gets a
     *  content_hash (ImageScanService) and a PENDING sync-queue row — exactly what
     *  a note arriving in the app does. `rel` may be nested (folder auto-created). */
    private Path createNote(String rel, String body) throws IOException {
        Path abs = VAULT.resolve(rel);
        Files.createDirectories(abs.getParent());
        String name = abs.getFileName().toString().replaceAll("\\.md$", "");
        String path = fileRepo.createNote(abs.getParent().toString(), name);
        fileRepo.updateNote(path, body);
        return Path.of(path);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String noteBody(String title) {
        return """
            ---
            sr-due: %s
            sr-interval: 3
            sr-ease: 230
            ---
            # %s

            Body of %s with some prose to sync.
            """.formatted(YESTERDAY, title, title);
    }

    // ── 1. upload ────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void upload_mirrorsVaultWithMetadata() throws Exception {
        createNote("Alpha.md",              noteBody("Alpha"));
        createNote("Nested/Beta.md",        noteBody("Beta"));
        createNote("Nested/Doc-unicode-Δü.md", noteBody("Unicode"));
        createNote("Doomed.md",             noteBody("Doomed"));
        byte[] png = new byte[2048];
        new java.util.Random(42).nextBytes(png);
        write("resources/images/pic.png", png);

        syncService.initialScan();   // .md rows already PENDING (createNote); queues the png
        long t0 = System.currentTimeMillis();
        syncService.uploadPending();
        uploadMillis = System.currentTimeMillis() - t0;

        List<DriveService.DriveFileInfo> remote = driveService.listAllFiles();
        uploadCount = remote.size();
        assertThat(remote).hasSize(5);
        for (DriveService.DriveFileInfo f : remote) {
            assertThat(f.vaultPath()).isNotBlank();
            assertThat(f.contentHash()).isNotBlank();
            byte[] local = Files.readAllBytes(VAULT.resolve(f.vaultPath()));
            assertThat(f.contentHash()).isEqualTo(sha256Of(f.vaultPath(), local));
            uploadedHashes.put(f.vaultPath(), f.contentHash());
        }
        assertThat(syncQueue.findByStatus("PENDING")).isEmpty();
        assertThat(syncQueue.findByStatus("FAILED")).isEmpty();
    }

    /** .md files hash their UTF-8 string form (see SyncService.uploadOne readFile). */
    private static String sha256Of(String relPath, byte[] raw) throws Exception {
        if (relPath.endsWith(".md")) {
            return sha256(new String(raw, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        }
        return sha256(raw);
    }

    // ── 2. fresh-device restore ──────────────────────────────────────────────

    @Test
    @Order(2)
    void freshDevice_downloadAll_restoresByteIdentical() throws Exception {
        // simulate a wiped machine: no files, no queue rows, empty index
        try (var stream = Files.walk(VAULT)) {
            stream.sorted(Comparator.reverseOrder())
                  .filter(p -> !p.equals(VAULT))
                  .forEach(p -> p.toFile().delete());
        }
        jdbc.execute("TRUNCATE sync_queue");
        noteIndex.forceResync(List.of());

        syncService.downloadAll();

        for (Map.Entry<String, String> e : uploadedHashes.entrySet()) {
            Path local = VAULT.resolve(e.getKey());
            assertThat(local).as("restored %s", e.getKey()).exists();
            assertThat(sha256Of(e.getKey(), Files.readAllBytes(local))).isEqualTo(e.getValue());
        }
        // restored notes are re-indexed and reviewable again
        assertThat(noteIndex.getAllPaths()).anyMatch(p -> p.endsWith("Alpha.md"));
    }

    // ── 3. local-wins conflict rule ──────────────────────────────────────────

    @Test
    @Order(3)
    void pendingLocalEdit_survivesDownload() throws Exception {
        String alpha = VAULT.resolve("Alpha.md").toString();
        String edited = noteBody("Alpha") + "\nLOCAL EDIT not yet uploaded.\n";
        fileRepo.updateNote(alpha, edited);   // marks the queue row PENDING

        syncService.downloadAll();            // Drive still has the old version

        assertThat(Files.readString(Path.of(alpha))).contains("LOCAL EDIT not yet uploaded");
        // …and the next upload pass publishes it
        syncService.uploadPending();
        assertThat(syncQueue.findByStatus("PENDING")).isEmpty();
    }

    // ── 4. tombstones ────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void softDelete_propagatesToDrive_andIsNotResurrected() throws Exception {
        String doomed = VAULT.resolve("Doomed.md").toString();
        fileRepo.softDeleteNote(doomed);      // tombstones the queue row

        syncService.uploadPending();          // processes tombstones before uploads

        assertThat(driveService.listAllFiles())
            .noneMatch(f -> "Doomed.md".equals(f.vaultPath()));

        syncService.downloadAll();            // must NOT bring Doomed.md back
        assertThat(Path.of(doomed)).doesNotExist();
    }

    // ── 5. phone lane: offline export + mailbox write-back ──────────────────

    @Test
    @Order(5)
    void phoneLane_exportDecryptable_mailboxGradeConsumed() throws Exception {
        int exported = offlineExport.exportReviewBundle(200);
        assertThat(exported).isGreaterThan(0);

        // the phone's read contract: find _offline/review-bundle.json.enc, decrypt it
        String offlineFolder = findFolder("_offline");
        String bundleId = findInFolder(offlineFolder, "review-bundle.json.enc");
        assertThat(bundleId).isNotNull();
        byte[] bundle = encryption.decrypt(driveService.downloadFile(bundleId));
        String json = new String(bundle, StandardCharsets.UTF_8);
        assertThat(json).contains("Alpha.md");

        // the phone's write contract: drop an encrypted grade envelope in _mailbox/
        driveService.listMailbox();  // ensures the _mailbox folder exists
        String mailboxFolder = findFolder("_mailbox");
        String alpha = VAULT.resolve("Alpha.md").toString().replace("\\", "\\\\");
        String envelope = """
            {"deviceId":"live-it-phone","events":[
              {"kind":"grade","eventId":"live-e1","notePath":"%s","band":"GOOD"}
            ]}""".formatted(alpha);
        byte[] enc = encryption.encrypt(envelope.getBytes(StandardCharsets.UTF_8));
        raw.files().create(
                new File().setName(System.currentTimeMillis() + "-live-it.enc")
                          .setParents(List.of(mailboxFolder)),
                new ByteArrayContent("application/octet-stream", enc))
            .setFields("id").execute();

        int applied = mailboxConsume.consumeAll();

        assertThat(applied).isEqualTo(1);
        assertThat(Files.readString(VAULT.resolve("Alpha.md"))).contains("fsrs-s:");
        assertThat(driveService.listMailbox()).isEmpty();   // consumed file hard-deleted
    }

    // ── 6. sustainability report ─────────────────────────────────────────────

    @Test
    @Order(6)
    void sustainabilityReport() throws Exception {
        Map<String, Long> quota = driveService.fetchQuota();
        double perFileS = uploadCount == 0 ? 0 : (uploadMillis / 1000.0) / uploadCount;
        long vaultNotes = 3300;   // current production vault size
        System.out.println("── DRIVE SUSTAINABILITY ─────────────────────────────");
        System.out.printf("  upload: %d files in %.1fs (%.2fs/file, concurrency %s)%n",
            uploadCount, uploadMillis / 1000.0, perFileS,
            System.getenv().getOrDefault("SYNC_UPLOAD_CONCURRENCY", "3"));
        System.out.printf("  full-vault backfill estimate (%d files): ~%.0f min%n",
            vaultNotes, vaultNotes * perFileS / 60);
        System.out.printf("  quota: %.2f / %.2f GB used%n",
            quota.getOrDefault("usedBytes", 0L) / 1e9,
            quota.getOrDefault("limitBytes", 0L) / 1e9);
        System.out.println("─────────────────────────────────────────────────────");
        assertThat(quota.getOrDefault("limitBytes", 0L)).isPositive();
    }

    // ── raw-client helpers ───────────────────────────────────────────────────

    private String findFolder(String name) throws IOException {
        return findInFolder(testRootId, name);
    }

    private String findInFolder(String parentId, String name) throws IOException {
        FileList found = raw.files().list()
            .setQ("name = '" + name + "' and '" + parentId + "' in parents and trashed = false")
            .setFields("files(id)").setPageSize(1).execute();
        return found.getFiles().isEmpty() ? null : found.getFiles().get(0).getId();
    }
}
