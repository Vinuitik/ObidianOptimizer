package com.obsidian.obsidian.bin;

import com.obsidian.obsidian.bin.BinService.BinItem;
import com.obsidian.obsidian.chrono.ChronoService;
import com.obsidian.obsidian.feed.FeedRepository;
import com.obsidian.obsidian.feed.SourceRepository;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import com.obsidian.obsidian.vaults.ActiveVault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bin: soft-delete -> list -> restore per kind, recursive restore, purge, read filters, vault deletion gone. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BinIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-bin-vault");
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

    @Autowired ActiveVault activeVault;
    @Autowired BinService bin;
    @Autowired BinRepository binRepo;
    @Autowired FileRepository fileRepo;
    @Autowired NoteIndexRepository noteIndex;
    @Autowired TrackRepository tracks;
    @Autowired SourceRepository sources;
    @Autowired FeedRepository feed;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext ctx;

    @BeforeEach
    void clean() throws IOException {
        activeVault.set(1L, VAULT);
        try (var s = Files.walk(VAULT)) {
            s.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(VAULT)).forEach(p -> p.toFile().delete());
        }
        jdbc.execute("TRUNCATE bin_entries, notes, note_links RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE track_schedule, track_items, tracks, feed_items, sources RESTART IDENTITY CASCADE");
    }

    private Path note(String rel, String body) throws IOException {
        Path p = VAULT.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        fileRepo.reindexAfterExternalWrite(p.toString());
        return p;
    }

    private List<BinItem> items(String kind) { return bin.list().get(kind); }

    // ── Files ────────────────────────────────────────────────────────────────

    @Test
    void note_roundTrip_restoresToOriginalPathAndReindexes() throws IOException {
        Path p = note("Area/sub/Idea.md", "# idea");
        fileRepo.softDeleteNote(p.toString());

        assertThat(p).doesNotExist();
        assertThat(noteIndex.getAllPaths(1L)).doesNotContain(p.toString());
        List<BinItem> listed = items("note");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).label()).isEqualTo("Idea");
        assertThat(listed.get(0).originalPath()).isEqualTo("Area/sub/Idea.md");
        assertThat(listed.get(0).deletedAt()).isNotNull();

        String restored = bin.restore("note", listed.get(0).id());

        assertThat(restored).isEqualTo("Area/sub/Idea.md");
        assertThat(p).exists();
        assertThat(noteIndex.getAllPaths(1L)).contains(p.toString());
        assertThat(items("note")).isEmpty();
        assertThat(VAULT.resolve("_trash").toFile().list()).isEmpty();
    }

    @Test
    void note_restore_whenOriginalNameTaken_addsSuffix() throws IOException {
        Path p = note("A/Dup.md", "old");
        fileRepo.softDeleteNote(p.toString());
        note("A/Dup.md", "new");

        String restored = bin.restore("note", items("note").get(0).id());

        assertThat(restored).isEqualTo("A/Dup (restored).md");
        assertThat(Files.readString(VAULT.resolve("A/Dup.md"))).isEqualTo("new");
        assertThat(Files.readString(VAULT.resolve(restored))).isEqualTo("old");
    }

    @Test
    void folder_isOneEntry_andRestoresEverythingInsideReindexed() throws IOException {
        note("Proj/a.md", "a");
        note("Proj/deep/b.md", "b");
        Files.writeString(VAULT.resolve("Proj/data.csv"), "x,y");
        fileRepo.softDeleteFolder(VAULT.resolve("Proj").toString());

        assertThat(items("folder")).hasSize(1);
        assertThat(items("note")).isEmpty();
        assertThat(noteIndex.getAllPaths(1L)).noneMatch(s -> s.contains("/Proj/"));

        bin.restore("folder", items("folder").get(0).id());

        assertThat(VAULT.resolve("Proj/deep/b.md")).exists();
        assertThat(VAULT.resolve("Proj/data.csv")).exists();
        assertThat(noteIndex.getAllPaths(1L))
            .contains(VAULT.resolve("Proj/a.md").toString(), VAULT.resolve("Proj/deep/b.md").toString());
        assertThat(items("folder")).isEmpty();
    }

    @Test
    void file_roundTrip_isNotIndexedWhenUnderExcludedDir() throws IOException {
        Path snap = VAULT.resolve("_inbox/_sources/s1.md");
        Files.createDirectories(snap.getParent());
        Files.writeString(snap, "snapshot");
        fileRepo.softDeleteFile(snap.toString());
        assertThat(items("file")).hasSize(1);

        bin.restore("file", items("file").get(0).id());

        assertThat(snap).exists();
        assertThat(noteIndex.getAllPaths(1L)).doesNotContain(snap.toString());
    }

    @Test
    void legacyTrash_isListedUnknown_andRestoresToInbox() throws IOException {
        Files.createDirectories(VAULT.resolve("_trash"));
        Files.writeString(VAULT.resolve("_trash/Old.md"), "legacy");

        List<BinItem> unknown = items("unknown");
        assertThat(unknown).extracting(BinItem::id).containsExactly("Old.md");
        assertThat(unknown.get(0).originalPath()).isNull();

        String restored = bin.restore("unknown", "Old.md");

        assertThat(restored).isEqualTo("_inbox/Old.md");
        assertThat(VAULT.resolve("_inbox/Old.md")).exists();
        assertThat(noteIndex.getAllPaths(1L)).contains(VAULT.resolve("_inbox/Old.md").toString());
        assertThat(items("unknown")).isEmpty();
    }

    @Test
    void unknown_idCannotEscapeTrash() {
        assertThatThrownBy(() -> bin.restore("unknown", "../x")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> bin.purge("unknown", "../x")).isInstanceOf(Exception.class);
    }

    // ── Tracks ───────────────────────────────────────────────────────────────

    @Test
    void track_roundTrip_restoresItemsAndSchedule_butNotItemsDeletedEarlier() {
        var t = tracks.create(1L, "Rust", "book", "manual");
        var keep = tracks.addItem(t.id(), "Ch1", null);
        var early = tracks.addItem(t.id(), "Ch2", null);
        tracks.completeItem(keep.id());
        tracks.setSchedule(t.id(), Map.of(0, 2));
        tracks.deleteItem(early.id());   // deleted on its own, before the track

        tracks.delete(1L, t.id());

        assertThat(tracks.listAll(1L)).isEmpty();
        assertThat(tracks.listActive(1L)).isEmpty();
        assertThat(tracks.get(1L, t.id())).isNull();
        assertThat(tracks.listItems(t.id())).isEmpty();
        assertThat(tracks.getItem(1L, keep.id())).isNull();
        assertThat(tracks.listProgressRows(1L)).isEmpty();
        assertThat(items("track")).extracting(BinItem::label).containsExactly("Rust");

        bin.restore("track", String.valueOf(t.id()));

        assertThat(tracks.get(1L, t.id())).isNotNull();
        assertThat(tracks.listItems(t.id())).extracting(TrackRepository.TrackItem::title).containsExactly("Ch1");
        assertThat(tracks.listItems(t.id()).get(0).status()).isEqualTo("done");
        assertThat(tracks.getSchedule(t.id())).isEqualTo(Map.of(0, 2));
        assertThat(items("track")).isEmpty();
        assertThat(items("track_item")).extracting(BinItem::id).containsExactly(String.valueOf(early.id()));
    }

    @Test
    void trackItem_roundTrip_andFiltersHideIt() {
        var t = tracks.create(1L, "T", "book", "manual");
        var i = tracks.addItem(t.id(), "Only", null);
        tracks.deleteItem(i.id());

        assertThat(tracks.listItems(t.id())).isEmpty();
        assertThat(tracks.countPendingItems(t.id())).isZero();
        assertThat(tracks.nextPendingItems(t.id(), 5)).isEmpty();
        assertThat(tracks.listProgressRows(1L).get(0).itemsTotal()).isZero();

        bin.restore("track_item", String.valueOf(i.id()));
        assertThat(tracks.listItems(t.id())).hasSize(1);
    }

    // ── Subscriptions + feed ─────────────────────────────────────────────────

    @Test
    void subscription_roundTrip_hidesAndRestoresItsFeedItems() {
        var s = sources.create(1L, "rss", "https://a/", null, "Blog A", null);
        feed.insertIfNew(1L, s.id(), "https://a/1", "One", null, null, null);
        feed.insertIfNew(1L, s.id(), "https://a/2", "Two", null, null, null);

        assertThat(sources.delete(1L, s.id())).isTrue();

        assertThat(sources.list(1L)).isEmpty();
        assertThat(sources.listActive(1L)).isEmpty();
        assertThat(sources.getByUrl(1L, "https://a/")).isNull();
        assertThat(feed.list(1L, "pending", 50)).isEmpty();
        assertThat(items("subscription")).extracting(BinItem::label).containsExactly("Blog A");

        bin.restore("subscription", String.valueOf(s.id()));

        assertThat(sources.list(1L)).hasSize(1);
        assertThat(feed.list(1L, "pending", 50)).hasSize(2);
        assertThat(items("subscription")).isEmpty();
    }

    @Test
    void subscription_resubscribeSupersedesBinnedCopy_andRestoreOfStaleConflicts() {
        var s = sources.create(1L, "rss", "https://a/", null, "A", null);
        sources.delete(1L, s.id());
        var again = sources.create(1L, "rss", "https://a/", null, "A2", null);   // must not violate UNIQUE
        assertThat(items("subscription")).isEmpty();
        assertThat(sources.list(1L)).extracting(SourceRepository.Source::id).containsExactly(again.id());
    }

    @Test
    void feed_binnedRoundTrip_andForeverKeepsDedup() {
        var s = sources.create(1L, "rss", "https://a/", null, "A", null);
        Long id = feed.insertIfNew(1L, s.id(), "https://a/1", "One", null, null, null);
        feed.bin(id);
        assertThat(items("feed")).extracting(BinItem::label).containsExactly("One");

        bin.restore("feed", String.valueOf(id));
        assertThat(feed.get(1L, id).status()).isEqualTo("pending");

        feed.bin(id);
        bin.purge("feed", String.valueOf(id));
        assertThat(items("feed")).isEmpty();
        assertThat(feed.list(1L, "pending", 50)).isEmpty();
        // the poller must not re-insert it as new
        assertThat(feed.insertIfNew(1L, s.id(), "https://a/1", "One", null, null, null)).isNull();
    }

    // ── Purge / retention ────────────────────────────────────────────────────

    @Test
    void purgeForever_deletesFileAndEntry() throws IOException {
        Path p = note("X.md", "x");
        fileRepo.softDeleteNote(p.toString());
        BinItem item = items("note").get(0);
        String trashRel = binRepo.get(1L, Long.parseLong(item.id())).trashPath();

        bin.purge("note", item.id());

        assertThat(VAULT.resolve(trashRel)).doesNotExist();
        assertThat(items("note")).isEmpty();
    }

    @Test
    void retention_purgesOnlyEntriesOlderThanCutoff_acrossKinds() throws IOException {
        Path old = note("Old.md", "o");
        Path fresh = note("Fresh.md", "f");
        fileRepo.softDeleteNote(old.toString());
        fileRepo.softDeleteNote(fresh.toString());
        jdbc.update("UPDATE bin_entries SET deleted_at = now() - interval '31 days' WHERE label = 'Old'");
        String oldTrash = jdbc.queryForObject("SELECT trash_path FROM bin_entries WHERE label = 'Old'", String.class);

        var tOld = tracks.create(1L, "OldTrack", "book", "manual");
        var tNew = tracks.create(1L, "NewTrack", "book", "manual");
        tracks.delete(1L, tOld.id());
        tracks.delete(1L, tNew.id());
        jdbc.update("UPDATE tracks SET deleted_at = now() - interval '31 days' WHERE id = ?", tOld.id());
        jdbc.update("UPDATE track_items SET deleted_at = now() - interval '31 days' WHERE track_id = ?", tOld.id());

        var sOld = sources.create(1L, "rss", "https://old/", null, "Old", null);
        sources.delete(1L, sOld.id());
        jdbc.update("UPDATE sources SET deleted_at = now() - interval '31 days' WHERE id = ?", sOld.id());

        int n = bin.purgeExpired(Instant.now().minus(30, ChronoUnit.DAYS));

        assertThat(n).isGreaterThanOrEqualTo(3);
        assertThat(VAULT.resolve(oldTrash)).doesNotExist();
        assertThat(items("note")).extracting(BinItem::label).containsExactly("Fresh");
        assertThat(items("track")).extracting(BinItem::label).containsExactly("NewTrack");
        assertThat(items("subscription")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sources", Integer.class)).isZero();
    }

    @Test
    void emptyAll_clearsEveryKind_includingLegacyTrash() throws IOException {
        fileRepo.softDeleteNote(note("N.md", "n").toString());
        Files.writeString(VAULT.resolve("_trash/legacy.md"), "l");
        var t = tracks.create(1L, "T", "book", "manual");
        tracks.delete(1L, t.id());
        var s = sources.create(1L, "rss", "https://a/", null, "A", null);
        sources.delete(1L, s.id());

        bin.emptyAll();

        bin.list().values().forEach(l -> assertThat(l).isEmpty());
        assertThat(VAULT.resolve("_trash").toFile().list()).isEmpty();
    }

    // ── Vault deletion is gone ───────────────────────────────────────────────

    @Test
    void vaultDeletion_endpointAndBeansAreGone() {
        assertThat(ctx.containsBean("vaultDeletionController")).isFalse();
        assertThat(ctx.containsBean("vaultDeletionWorker")).isFalse();
        assertThatThrownBy(() -> Class.forName("com.obsidian.obsidian.vaults.VaultDeletionController"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThat(ctx.getBeansOfType(
            org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)).isNotNull();
    }
}
