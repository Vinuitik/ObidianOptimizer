package com.obsidian.obsidian.vaults;

import com.obsidian.obsidian.cards.AssignmentRepository;
import com.obsidian.obsidian.cards.CardRepository;
import com.obsidian.obsidian.cards.NoteReviewRepository;
import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.common.OutboxRepository;
import com.obsidian.obsidian.common.PipelineFailureRepository;
import com.obsidian.obsidian.feed.FeedRepository;
import com.obsidian.obsidian.feed.SourceRepository;
import com.obsidian.obsidian.ml.NoteChunkRepository;
import com.obsidian.obsidian.ml.PendingImageJobRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.notes.NoteLinkRepository;
import com.obsidian.obsidian.sync.SyncQueueRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import com.obsidian.obsidian.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pre-vaults schema (vault_id columns + vaults table stripped) -> re-run initSchema -> backfill. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class VaultMigrationIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-vaults-vault");
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

    @Autowired JdbcTemplate jdbc;
    @Autowired VaultRepository vaults;
    @Autowired ActiveVault activeVault;
    @Autowired NoteIndexRepository notes;
    @Autowired NoteLinkRepository links;
    @Autowired NoteReviewRepository reviews;
    @Autowired CardRepository cards;
    @Autowired SyncQueueRepository syncQueue;
    @Autowired WorkspaceRepository workspace;
    @Autowired PendingImageJobRepository imageJobs;
    @Autowired NoteChunkRepository chunks;
    @Autowired AssignmentRepository assignments;
    @Autowired CaptureRepository capture;
    @Autowired TrackRepository tracks;
    @Autowired SourceRepository sources;
    @Autowired FeedRepository feed;
    @Autowired OutboxRepository outbox;
    @Autowired PipelineFailureRepository failures;

    private void rerunInitSchema() {
        vaults.initSchema();
        notes.initSchema(); links.initSchema(); reviews.initSchema(); cards.initSchema();
        syncQueue.initSchema(); workspace.initSchema(); imageJobs.initSchema(); chunks.initSchema();
        assignments.initSchema(); capture.initSchema(); tracks.initSchema(); sources.initSchema();
        feed.initSchema(); outbox.initSchema(); failures.initSchema();
    }

    private List<String> vaultIdTables() {
        return jdbc.queryForList("""
            SELECT table_name FROM information_schema.columns
            WHERE table_schema = 'public' AND column_name = 'vault_id' ORDER BY table_name
            """, String.class);
    }

    @Test
    void oldSchemaBackfillsToVault1_andIsIdempotent() {
        List<String> expected = List.of("assignments", "capture", "card_flags", "card_gen_attempts", "cards",
            "feed_items", "note_chunks", "note_links", "note_reviews", "notes", "outbox_events",
            "pending_image_jobs", "pipeline_failures", "scope_state", "sources", "sync_queue",
            "track_item_groups", "tracks", "workspace_items");
        assertThat(vaultIdTables()).containsAll(expected);

        // Strip back to the pre-vaults schema.
        for (String t : vaultIdTables()) jdbc.execute("ALTER TABLE " + t + " DROP COLUMN vault_id");
        jdbc.execute("DROP TABLE vaults CASCADE");
        // DROP COLUMN also dropped every key that contained vault_id: restore the pre-vaults keys.
        for (String ddl : List.of(
            "ALTER TABLE notes ADD PRIMARY KEY (path)",
            "ALTER TABLE note_links ADD PRIMARY KEY (source_path, target_name)",
            "ALTER TABLE note_reviews ADD PRIMARY KEY (note_path)",
            "ALTER TABLE card_gen_attempts ADD PRIMARY KEY (note_path)",
            "ALTER TABLE sync_queue ADD PRIMARY KEY (path)",
            "ALTER TABLE workspace_items ADD PRIMARY KEY (filename)",
            "ALTER TABLE scope_state ADD PRIMARY KEY (scope, type)",
            "ALTER TABLE cards ADD UNIQUE (note_path, card_hash)",
            "ALTER TABLE pending_image_jobs ADD UNIQUE (note_path, image_path)",
            "ALTER TABLE sources ADD UNIQUE (url)",
            "ALTER TABLE feed_items ADD UNIQUE (item_url)",
            "CREATE UNIQUE INDEX uq_note_chunks_path_source_idx ON note_chunks(note_path, source, chunk_index)"))
            jdbc.execute(ddl);

        jdbc.update("INSERT INTO notes(path, title, modified_at) VALUES ('/vault/a.md','a',1),('/vault/b.md','b',2)");
        jdbc.update("INSERT INTO note_links(source_path, target_name) VALUES ('/vault/a.md','b')");
        jdbc.update("INSERT INTO note_reviews(note_path, stability, difficulty, last_review, due) "
            + "VALUES ('/vault/a.md', 1, 1, now(), now())");
        jdbc.update("INSERT INTO sync_queue(path, content_hash) VALUES ('/vault/a.md','h')");
        jdbc.update("INSERT INTO workspace_items(filename, type, added_at) VALUES ('f.png','image',1)");
        jdbc.update("INSERT INTO tracks(title, type, source) VALUES ('t','x','manual')");
        jdbc.update("INSERT INTO pipeline_failures(source, stage, input_payload) VALUES ('s','st','{}'::jsonb)");

        Map<String, Integer> before = new LinkedHashMap<>();
        for (String t : expected) before.put(t, jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class));
        assertThat(before.get("notes")).isEqualTo(2);

        for (int boot = 0; boot < 2; boot++) {   // second pass = idempotent reboot
            rerunInitSchema();
            assertThat(vaultIdTables()).containsAll(expected);
            for (String t : expected) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class))
                    .as("count " + t).isEqualTo(before.get(t));
                assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + t + " WHERE vault_id IS DISTINCT FROM 1", Integer.class))
                    .as("non-1 vault_id in " + t).isZero();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vaults", Integer.class)).isEqualTo(1);
            Map<String, Object> v = jdbc.queryForMap("SELECT * FROM vaults WHERE id = 1");
            assertThat(v.get("container_path")).isEqualTo("/vault");
            assertThat(v.get("active")).isEqualTo(true);
            assertThat(v.get("is_primary")).isEqualTo(true);
            assertThat(jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('vaults','id'))", Long.class))
                .isGreaterThan(1L);
        }
    }

    private List<String> keyDefs(String table) {
        return jdbc.queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
            + "WHERE conrelid = '" + table + "'::regclass AND contype IN ('p','u')", String.class);
    }

    @Test
    void keysIncludeVaultId_samePathsCoexistAcrossVaults_andUpsertsDedupeWithinVault() {
        for (int boot = 0; boot < 2; boot++) {   // second pass = guarded swaps are no-ops
            rerunInitSchema();
            for (String t : List.of("notes", "note_links", "note_reviews", "card_gen_attempts", "sync_queue",
                    "workspace_items", "scope_state", "cards", "pending_image_jobs", "sources", "feed_items")) {
                assertThat(keyDefs(t)).as("keys of " + t).isNotEmpty()
                    .allMatch(d -> d.equals("PRIMARY KEY (id)") || d.contains("vault_id"));
            }
            assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_note_chunks_path_source_idx'",
                String.class)).contains("vault_id");
            assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_note_chunks_bm25'",
                String.class)).contains("vault_id");
        }
        jdbc.update("INSERT INTO vaults(id, name, container_path, active) VALUES (2, 'Two', '/vaults/2', false) ON CONFLICT DO NOTHING");
        jdbc.update("DELETE FROM notes"); jdbc.update("DELETE FROM sync_queue");
        jdbc.update("DELETE FROM workspace_items"); jdbc.update("DELETE FROM note_chunks");
        jdbc.update("DELETE FROM note_reviews"); jdbc.update("DELETE FROM sources");

        // Same path / filename in vault 1 and vault 2: no conflict.
        for (long v : new long[]{1, 2}) {
            jdbc.update("INSERT INTO notes(path,title,modified_at,vault_id) VALUES ('rel/a.md','a',1,?)", v);
            jdbc.update("INSERT INTO workspace_items(filename,type,added_at,vault_id) VALUES ('f.png','image',1,?)", v);
            jdbc.update("INSERT INTO sync_queue(path,content_hash,vault_id) VALUES ('/vault/a.md','h',?)", v);
            jdbc.update("INSERT INTO sources(kind,url,vault_id) VALUES ('rss','http://x/feed',?)", v);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notes WHERE path='rel/a.md'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sources WHERE url='http://x/feed'", Integer.class)).isEqualTo(2);

        // Within a vault the real repository upserts still dedupe (default vault 1) and don't touch vault 2.
        syncQueue.markPending(1L, "/vault/a.md", "h2");
        syncQueue.markPending(1L, "/vault/a.md", "h3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_queue WHERE vault_id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_hash FROM sync_queue WHERE vault_id=1", String.class)).isEqualTo("h3");
        assertThat(jdbc.queryForObject("SELECT content_hash FROM sync_queue WHERE vault_id=2", String.class)).isEqualTo("h");
        workspace.insert(1L, "f.png", "image", null, 1);
        workspace.insert(1L, "f.png", "pdf", null, 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_items WHERE vault_id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM workspace_items WHERE vault_id=1", String.class)).isEqualTo("pdf");
        assertThat(jdbc.queryForObject("SELECT type FROM workspace_items WHERE vault_id=2", String.class)).isEqualTo("image");
        chunks.upsertChunkTextOnly(1L, "/vault/a.md", 0, "text", "hello", "c1");
        chunks.upsertChunkTextOnly(1L, "/vault/a.md", 0, "text", "hello2", "c2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_chunks", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO note_chunks(note_path,chunk_index,source,text,content_hash,vault_id) "
            + "VALUES ('/vault/a.md',0,'text','other','c',2)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_chunks", Integer.class)).isEqualTo(2);
        reviews.upsert(1L, "/vault/a.md", 1, 1, new java.sql.Timestamp(0), new java.sql.Timestamp(0), null, null);
        reviews.upsert(1L, "/vault/a.md", 1, 1, new java.sql.Timestamp(0), new java.sql.Timestamp(0), null, null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_reviews", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reps FROM note_reviews", Integer.class)).isEqualTo(2);

        // Leave the shared DB clean for the other test (it asserts exact counts / vault 1 only).
        for (String t : List.of("notes", "sync_queue", "workspace_items", "note_chunks", "note_reviews", "sources"))
            jdbc.update("DELETE FROM " + t);
        jdbc.update("DELETE FROM vaults WHERE id = 2");
    }

    @Test
    void activeVaultLoadsFromDb() {
        assertThat(activeVault.id()).isEqualTo(1L);
        assertThat(activeVault.root()).isEqualTo(Path.of("/vault"));
    }
}
