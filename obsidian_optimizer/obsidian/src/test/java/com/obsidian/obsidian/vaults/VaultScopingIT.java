package com.obsidian.obsidian.vaults;

import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.cards.AssignmentRepository;
import com.obsidian.obsidian.cards.CardRepository;
import com.obsidian.obsidian.cards.NoteReviewRepository;
import com.obsidian.obsidian.chrono.ChronoService;
import com.obsidian.obsidian.feed.FeedRepository;
import com.obsidian.obsidian.feed.SourceRepository;
import com.obsidian.obsidian.ml.NoteChunkRepository;
import com.obsidian.obsidian.ml.PendingImageJobRepository;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import com.obsidian.obsidian.workspace.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3: two vaults (two temp dirs, second row in {@code vaults}) share one database; nothing
 * written in one may be readable through the other. The same absolute-looking note path is
 * deliberately reused in both vaults for the DB-only tables — the composite keys allow it,
 * so a query that forgot {@code vault_id} would see two rows and fail here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class VaultScopingIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path V1;
    static final Path V2;
    static {
        try {
            V1 = Files.createTempDirectory("obsidian-scope-v1");
            V2 = Files.createTempDirectory("obsidian-scope-v2");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          V1::toString);
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockBean ChronoService chronoService;

    @Autowired JdbcTemplate jdbc;
    @Autowired ActiveVault activeVault;
    @Autowired FileRepository fileRepo;
    @Autowired NoteIndexRepository notes;
    @Autowired NoteReviewRepository reviews;
    @Autowired CardRepository cards;
    @Autowired AssignmentRepository assignments;
    @Autowired NoteChunkRepository chunks;
    @Autowired PendingImageJobRepository imageJobs;
    @Autowired WorkspaceRepository workspace;
    @Autowired CaptureRepository capture;
    @Autowired SourceRepository sources;
    @Autowired FeedRepository feed;
    @Autowired TrackRepository tracks;

    @BeforeEach
    void twoVaults() {
        jdbc.update("""
            INSERT INTO vaults(id, name, container_path, active, is_primary)
            VALUES (2, 'Second', ?, false, false) ON CONFLICT DO NOTHING
            """, V2.toString());
        activeVault.set(1L, V1);
    }

    @AfterEach
    void clean() throws IOException {
        for (Path root : List.of(V1, V2)) {
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(root))
                      .forEach(p -> p.toFile().delete());
            }
        }
        jdbc.execute("TRUNCATE notes, note_links, note_reviews, cards, card_flags, card_gen_attempts, "
            + "scope_state, assignments, note_chunks, pending_image_jobs, workspace_items, capture, "
            + "sources, feed_items, tracks, track_items, track_item_groups CASCADE");
        activeVault.set(1L, V1);
    }

    private void as(long id) { activeVault.set(id, id == 1L ? V1 : V2); }

    @Test
    void notes_neverLeakAcrossVaults() throws IOException {
        as(1);
        String a = fileRepo.createNote(V1.toString(), "Alpha");
        as(2);
        String b = fileRepo.createNote(V2.toString(), "Beta");

        assertThat(notes.getAllPaths(1)).containsExactly(a);
        assertThat(notes.getAllPaths(2)).containsExactly(b);
        assertThat(fileRepo.getNoteNames()).containsExactly(b);            // active = 2
        as(1);
        assertThat(fileRepo.getNoteNames()).containsExactly(a);            // active = 1
        assertThat(jdbc.queryForObject("SELECT vault_id FROM notes WHERE path = ?", Long.class, b)).isEqualTo(2L);
        // a vault-1 request cannot touch a vault-2 note's index row
        notes.delete(1, b);
        assertThat(notes.getAllPaths(2)).containsExactly(b);
        // file access is confined to the active vault's root
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> fileRepo.updateNote(b, "x"));
    }

    @Test
    void scanner_syncOnlyTouchesItsOwnVault() throws IOException {
        Files.writeString(V1.resolve("OnDisk1.md"), "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n");
        Files.writeString(V2.resolve("OnDisk2.md"), "---\nsr-due: 2025-01-01\nsr-interval: 3\nsr-ease: 200\n---\n");
        as(1);
        notes.syncWithDisk(1, fileRepo.listMdPaths().stream().map(Path::toFile).toList());
        as(2);
        notes.syncWithDisk(2, fileRepo.listMdPaths().stream().map(Path::toFile).toList());
        // re-syncing vault 1 with vault 1's (unchanged) files must not delete vault 2's rows
        as(1);
        notes.syncWithDisk(1, fileRepo.listMdPaths().stream().map(Path::toFile).toList());

        assertThat(notes.getAllPaths(1)).hasSize(1).allMatch(p -> p.endsWith("OnDisk1.md"));
        assertThat(notes.getAllPaths(2)).hasSize(1).allMatch(p -> p.endsWith("OnDisk2.md"));
    }

    @Test
    void reviewQueue_isPerVault() throws IOException {
        as(1);
        String a = fileRepo.createNote(V1.toString(), "DueA");
        as(2);
        String b = fileRepo.createNote(V2.toString(), "DueB");
        jdbc.update("UPDATE notes SET sr_due = CURRENT_DATE - 1");

        assertThat(notes.getReviewNotesPaged(1, 0, 50).notes()).containsExactly(a);
        assertThat(notes.getReviewNotesPaged(2, 0, 50).notes()).containsExactly(b);
        assertThat(notes.getReviewNotesPagedWithCards(2, 0, 50).notes()).extracting("path").containsExactly(b);

        // FSRS state for the SAME path in two vaults stays separate
        Path shared = Path.of("/shared/Note.md");
        var now = java.sql.Timestamp.from(java.time.Instant.now());
        reviews.upsert(1, shared.toString(), 1.0, 5.0, now, now, null, null);
        reviews.upsert(2, shared.toString(), 9.0, 5.0, now, now, null, null);
        assertThat(reviews.find(1, shared.toString()).stability()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(reviews.find(2, shared.toString()).stability()).isCloseTo(9.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(reviews.findDue(1, 10)).hasSize(1);
        reviews.delete(1, shared.toString());
        assertThat(reviews.find(2, shared.toString())).isNotNull();
    }

    private float[] vec(float seed) {
        float[] v = new float[768];
        java.util.Arrays.fill(v, seed);
        return v;
    }

    @Test
    void search_bm25AndVector_filterByVault() {
        chunks.upsertChunk(1, "/shared/N.md", 0, "text", "zebra quantum banana", vec(0.5f), "h1");
        chunks.upsertChunk(2, "/shared/N.md", 0, "text", "zebra quantum banana", vec(0.5f), "h2");
        chunks.upsertChunk(2, "/only2/M.md", 0, "text", "unique pomegranate word", vec(0.4f), "h3");

        assertThat(chunks.findByTextSearch(1, "banana", 10)).hasSize(1);
        assertThat(chunks.findByTextSearch(2, "banana", 10)).hasSize(1);
        assertThat(chunks.findByTextSearch(1, "pomegranate", 10)).isEmpty();
        assertThat(chunks.findByTextSearch(2, "pomegranate", 10)).hasSize(1);

        assertThat(chunks.findByVectorSimilarity(1, vec(0.4f), 10))
            .extracting("notePath").containsExactly("/shared/N.md");
        assertThat(chunks.findByVectorSimilarity(2, vec(0.4f), 10)).hasSize(2);

        assertThat(chunks.getContentHash(1, "/shared/N.md", "text", 0)).isEqualTo("h1");
        assertThat(chunks.getContentHash(2, "/shared/N.md", "text", 0)).isEqualTo("h2");
        assertThat(chunks.getContentHash(1, "/only2/M.md", "text", 0)).isNull();
        assertThat(chunks.countDistinctNotesForSource(1, "text")).isEqualTo(1);
        assertThat(chunks.countDistinctNotesForSource(2, "text")).isEqualTo(2);
        // the embed queue is per vault too
        chunks.upsertChunkTextOnly(2, "/only2/M.md", 1, "image", "caption", "h4");
        assertThat(chunks.findChunksNeedingEmbedding(1, 10)).isEmpty();
        assertThat(chunks.findChunksNeedingEmbedding(2, 10)).hasSize(1)
            .allSatisfy(c -> assertThat(c.vaultId()).isEqualTo(2L));
    }

    @Test
    void imageJobs_areScopedAndCarryTheirVault() throws IOException {
        as(1);
        String a = fileRepo.createNote(V1.toString(), "ImgA");
        as(2);
        String b = fileRepo.createNote(V2.toString(), "ImgB");
        imageJobs.upsertPending(1, a, "x.png");
        imageJobs.upsertPending(2, b, "y.png");

        assertThat(imageJobs.findPending(1, 10)).hasSize(1).allSatisfy(j -> assertThat(j.getVaultId()).isEqualTo(1L));
        assertThat(imageJobs.findPending(2, 10)).hasSize(1).allSatisfy(j -> assertThat(j.getVaultId()).isEqualTo(2L));
        assertThat(imageJobs.countByStatus(1).get("PENDING")).isEqualTo(1);
    }

    private void card(long vault, String path, String hash, int difficulty) {
        jdbc.update("""
            INSERT INTO cards(note_path, type, payload, difficulty, card_hash, source_hash, vault_id)
            VALUES (?, 'open', '{"question":"q"}'::jsonb, ?, ?, 'src', ?)
            """, path, difficulty, hash, vault);
    }

    @Test
    void cards_drawsStatsAndFlagsStayInsideTheirVault() {
        card(1, "/shared/Folder/N.md", "h", 2);
        card(2, "/shared/Folder/N.md", "h", 2);       // same note path + hash: legal across vaults
        card(2, "/shared/Folder/M.md", "m", 4);

        assertThat(cards.findActiveByNote(1, "/shared/Folder/N.md")).hasSize(1);
        assertThat(cards.findActiveByNote(2, "/shared/Folder/N.md")).hasSize(1);
        assertThat(cards.stats(1).get("active_cards")).isEqualTo(1L);
        assertThat(cards.stats(2).get("active_cards")).isEqualTo(2L);

        assertThat(assignments.notesInScope(1, "/shared/Folder")).containsExactly("/shared/Folder/N.md");
        assertThat(assignments.notesInScope(2, "/shared/Folder")).hasSize(2);
        assertThat(assignments.typesInScope(1, "/shared/Folder")).containsExactly("open");

        int cycle = assignments.currentCycle(1, "/shared/Folder/N.md", "tier");
        assertThat(assignments.drawCardInTier(1, "/shared/Folder/N.md", 1, 5, cycle, new UUID[0])).isNotNull();
        // vault 1's draw marked only vault 1's card as drawn; vault 2's bag is untouched
        int cycle2 = assignments.currentCycle(2, "/shared/Folder/N.md", "tier");
        assertThat(assignments.drawCardInTier(2, "/shared/Folder/N.md", 1, 5, cycle2, new UUID[0])).isNotNull();
        assertThat(assignments.drawCardInTier(1, "/shared/Folder/M.md", 1, 5, cycle, new UUID[0])).isNull();

        // flagging a vault-2 card through vault 1 is a no-op
        UUID v2Card = jdbc.queryForObject(
            "SELECT id FROM cards WHERE vault_id = 2 AND card_hash = 'm'", UUID.class);
        assertThat(cards.flag(1, v2Card, "bad")).isNull();
        assertThat(cards.flag(2, v2Card, "bad")).isEqualTo("/shared/Folder/M.md");
        assertThat(assignments.findCard(1, v2Card)).isNull();

        UUID id = assignments.insertAssignment(2, "/shared", 5, 5, new UUID[]{v2Card}, "{}");
        assertThat(assignments.findAssignment(1, id)).isNull();
        assertThat(assignments.findAssignment(2, id)).isNotNull();
    }

    @Test
    void cardWorklist_isPerVault() {
        // two review notes with a body_hash, one per vault; vault 1's worklist never sees vault 2's
        jdbc.update("INSERT INTO notes(path, title, sr_due, modified_at, body_hash, vault_id) "
            + "VALUES ('/w/A.md','A', CURRENT_DATE, 1, 'bh', 1), ('/w/A.md','A', CURRENT_DATE, 1, 'bh', 2), "
            + "('/w/B.md','B', CURRENT_DATE, 1, 'bh', 2)");
        assertThat(cards.findNotesNeedingCards(1, 10)).hasSize(1);
        assertThat(cards.findNotesNeedingCards(2, 10)).hasSize(2);
        cards.recordAttempt(1, "/w/A.md", "bh");
        assertThat(cards.findNotesNeedingCards(1, 10)).isEmpty();
        assertThat(cards.findNotesNeedingCards(2, 10)).hasSize(2);   // vault 2's attempt ledger untouched
    }

    @Test
    void tracks_feedSources_capture_workspace_areScoped() {
        var t1 = tracks.create(1, "Track one", "custom", "manual");
        var t2 = tracks.create(2, "Track two", "custom", "manual");
        var item = tracks.addItem(t1.id(), "item", null);

        assertThat(tracks.listAll(1)).extracting("title").containsExactly("Track one");
        assertThat(tracks.listAll(2)).extracting("title").containsExactly("Track two");
        assertThat(tracks.listActive(1)).hasSize(1);
        assertThat(tracks.get(2, t1.id())).isNull();
        assertThat(tracks.getItem(2, item.id())).isNull();
        assertThat(tracks.getItem(1, item.id())).isNotNull();
        assertThat(tracks.listProgressRows(2)).extracting(r -> r.track().id()).containsExactly(t2.id());
        assertThat(tracks.getOrCreatePassive(1).id()).isNotEqualTo(tracks.getOrCreatePassive(2).id());
        tracks.delete(2, t1.id());                       // wrong vault: no effect
        assertThat(tracks.get(1, t1.id())).isNotNull();

        var s1 = sources.create(1, "rss", "https://same/feed", null, "S", null, null);
        var s2 = sources.create(2, "rss", "https://same/feed", null, "S", null, null);   // (vault,url) unique
        assertThat(sources.list(1)).extracting("id").containsExactly(s1.id());
        assertThat(sources.getByUrl(2, "https://same/feed").id()).isEqualTo(s2.id());
        assertThat(sources.get(2, s1.id())).isNull();
        assertThat(sources.delete(2, s1.id())).isFalse();
        feed.insertIfNew(1, s1.id(), "https://same/item", "T", null, null, null);
        feed.insertIfNew(2, s2.id(), "https://same/item", "T", null, null, null);        // same url, other vault
        assertThat(feed.list(1, "pending", 10)).hasSize(1);
        assertThat(feed.list(2, "pending", 10)).hasSize(1);
        long f1 = feed.list(1, "pending", 10).get(0).id();
        assertThat(feed.get(2, f1)).isNull();

        capture.enqueue(1, "cap-1", "url", "https://x/y", null, "Cap");
        assertThat(capture.listAll(1)).hasSize(1);
        assertThat(capture.listAll(2)).isEmpty();
        assertThat(capture.existsForSource(2, "https://x/y")).isFalse();
        assertThat(capture.existsLiveForSource(1, "https://x/y")).isTrue();
        assertThat(capture.findQueued(2, 10)).isEmpty();
        assertThat(capture.findQueued(1, 10)).allSatisfy(c -> assertThat(c.vaultId()).isEqualTo(1L));

        workspace.insert(1, "f.pdf", "pdf", null, 0);
        assertThat(workspace.listAll(1)).hasSize(1);
        assertThat(workspace.listAll(2)).isEmpty();
        workspace.delete(2, "f.pdf");
        assertThat(workspace.listAll(1)).hasSize(1);
    }
}
