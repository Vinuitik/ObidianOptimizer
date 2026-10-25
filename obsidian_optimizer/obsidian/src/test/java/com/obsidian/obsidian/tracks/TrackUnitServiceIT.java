package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.chrono.ChronoService;
import com.obsidian.obsidian.notes.FrontmatterParser.NoteMetadata;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackUnitService.Unit;
import com.obsidian.obsidian.tracks.TrackUnitService.UnitsResult;
import com.obsidian.obsidian.vaults.ActiveVault;
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
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Learn units: lazy assignment/backfill, General track, daily picker, carry-over, move, schema key. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TrackUnitServiceIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-units-vault");
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
    @Autowired TrackUnitService units;
    @Autowired TrackRepository tracks;
    @Autowired NoteIndexRepository noteIndex;
    @Autowired CaptureRepository captures;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate MON = LocalDate.of(2026, 10, 19);

    @BeforeEach
    void clean() {
        activeVault.set(1L, VAULT);
        jdbc.execute("TRUNCATE today_units, notes, capture RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE track_schedule, track_items, tracks, feed_items, sources RESTART IDENTITY CASCADE");
    }

    /** Stage a note at {@code _inbox/<cap>[/<chapter>]/<name>.md} in the index. */
    private void inboxNote(String cap, String chapter, String name, int seq) {
        String rel = "/_inbox/" + cap + (chapter == null ? "" : "/" + chapter) + "/" + name + ".md";
        noteIndex.upsert(1L, VAULT + rel, name, new NoteMetadata(null, null, null, cap, seq), 1L);
    }

    private void capture(String id, String title, long createdAt) {
        captures.create(1L, id, "web_dom", "http://x/" + id, null, title);
        jdbc.update("UPDATE capture SET created_at = ? WHERE id = ?", createdAt, id);
    }

    @Test
    void generalTrack_autoCreated_idempotent_budgetOnAllWeekdays() {
        Track a = tracks.getOrCreateGeneral(1L);
        Track b = tracks.getOrCreateGeneral(1L);

        assertThat(b.id()).isEqualTo(a.id());
        assertThat(a.title()).isEqualTo("General");
        assertThat(tracks.getSchedule(a.id())).hasSize(7).containsValues(1);
    }

    @Test
    void today_assignsUnassignedGroupsToGeneral_andPicksOnePerDay() {
        capture("c1", "Video One", 100);
        capture("c2", "Video Two", 200);
        inboxNote("c1", null, "a", 1);
        inboxNote("c1", null, "b", 2);
        inboxNote("c2", null, "c", 1);

        UnitsResult r = units.today(MON);

        assertThat(r.units()).hasSize(1);
        Unit u = r.units().get(0);
        assertThat(u.groupKey()).isEqualTo("c1");   // capture order: oldest first
        assertThat(u.noteCount()).isEqualTo(2);
        assertThat(u.title()).isEqualTo("Video One");
        assertThat(u.trackTitle()).isEqualTo("General");
        assertThat(r.backlogCount()).isEqualTo(1);
        assertThat(tracks.unitAssignments(1L)).containsOnlyKeys("c1", "c2");   // backfill assigned both
    }

    @Test
    void chapters_areSeparateUnits_andKeyedByFolderSegment() {
        capture("bk", "Book", 100);
        inboxNote("bk", "ch-1", "x", 1);
        inboxNote("bk", "ch-2", "y", 2);

        UnitsResult r = units.today(MON);

        assertThat(r.units()).extracting(Unit::groupKey).containsExactly("bk/ch-1");
        assertThat(r.units().get(0).chapter()).isEqualTo("ch-1");
        assertThat(r.backlogCount()).isEqualTo(1);
    }

    @Test
    void unfinishedUnitCarriesOver_andFinishedOneAdvances() {
        capture("c1", "One", 100);
        capture("c2", "Two", 200);
        inboxNote("c1", null, "a", 1);
        inboxNote("c1", null, "b", 2);
        inboxNote("c2", null, "c", 1);
        units.today(MON);                                   // picks c1
        jdbc.update("DELETE FROM notes WHERE path LIKE '%/c1/b.md'");   // c1 still has note a

        UnitsResult tue = units.today(MON.plusDays(1));

        assertThat(tue.units()).extracting(Unit::groupKey).containsExactly("c1");   // stays, uses the 1 budget

        jdbc.update("DELETE FROM notes WHERE path LIKE '%/c1/a.md'");               // c1 finished
        UnitsResult wed = units.today(MON.plusDays(2));

        assertThat(wed.units()).extracting(Unit::groupKey).containsExactly("c2");
    }

    @Test
    void finishingTodaysUnit_doesNotRefillToday() {
        capture("c1", "One", 100);
        capture("c2", "Two", 200);
        inboxNote("c1", null, "a", 1);
        inboxNote("c2", null, "c", 1);
        units.today(MON);

        jdbc.update("DELETE FROM notes WHERE path LIKE '%/c1/a.md'");
        UnitsResult again = units.today(MON);

        assertThat(again.units()).isEmpty();
        assertThat(again.backlogCount()).isEqualTo(1);
    }

    @Test
    void move_rehomesUnit_andPickerUsesNewTrackBudget() {
        capture("c1", "One", 100);
        inboxNote("c1", null, "a", 1);
        Track rust = tracks.create(1L, "Rust", "custom", "manual");
        tracks.setSchedule(rust.id(), Map.of(1, 2));   // Tuesday only
        units.today(MON);                              // assigned to General, picked Mon

        assertThat(units.move("c1", null, rust.id()).id()).isEqualTo(rust.id());
        UnitsResult tue = units.today(MON.plusDays(1));

        assertThat(tue.units()).hasSize(1);
        assertThat(tue.units().get(0).trackId()).isEqualTo(rust.id());
        assertThat(tue.units().get(0).trackTitle()).isEqualTo("Rust");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM track_item_groups WHERE capture_id = 'c1'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void subscriptionTrack_winsOverGeneral() {
        capture("c1", "One", 100);
        inboxNote("c1", null, "a", 1);
        Track feedTrack = tracks.create(1L, "Feed Track", "custom", "manual");
        jdbc.update("INSERT INTO sources(kind, url, target_track_id, vault_id) VALUES ('rss', 'http://f', ?, 1)", feedTrack.id());
        Long srcId = jdbc.queryForObject("SELECT id FROM sources", Long.class);
        jdbc.update("INSERT INTO feed_items(source_id, item_url, title, status, capture_id, vault_id) VALUES (?, 'http://x', 't', 'accepted', 'c1', 1)", srcId);

        units.today(MON);

        assertThat(tracks.unitAssignments(1L)).containsEntry("c1", feedTrack.id());
    }

    @Test
    void nullChapterStillUniquePerCapture() {
        Track t = tracks.create(1L, "T", "custom", "manual");
        var g1 = tracks.getOrCreateGroup(1L, t.id(), "cx", "A", null);
        var g2 = tracks.getOrCreateGroup(1L, t.id(), "cx", "A", null);
        var g3 = tracks.getOrCreateGroup(1L, t.id(), "cx", "ch", "A", null);

        assertThat(g2.id()).isEqualTo(g1.id());
        assertThat(g3.id()).isNotEqualTo(g1.id());
    }
}
