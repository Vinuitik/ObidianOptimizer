package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItem;
import com.obsidian.obsidian.tracks.TrackRepository.TrackItemGroup;
import com.obsidian.obsidian.tracks.TrackRepository.TrackProgressRow;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TrackRepositoryIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-tracks-vault");
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

    @Autowired TrackRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE track_schedule, track_items, tracks RESTART IDENTITY CASCADE");
    }

    // ── Tracks CRUD ──────────────────────────────────────────────────────────

    @Test
    void create_thenGet_roundTrips() {
        Track created = repo.create("Rust Book", "book", "manual");

        assertThat(created.title()).isEqualTo("Rust Book");
        assertThat(created.type()).isEqualTo("book");
        assertThat(created.status()).isEqualTo("active");
        assertThat(created.includeInProgress()).isTrue();
        assertThat(created.deadline()).isNull();

        Track fetched = repo.get(created.id());
        assertThat(fetched).isEqualTo(created);
    }

    @Test
    void update_partial_onlyChangesGivenFields() {
        Track t = repo.create("Rust Book", "book", "manual");

        Track updated = repo.update(t.id(), "Rust Book v2", null, null, null, null, null, false);

        assertThat(updated.title()).isEqualTo("Rust Book v2");
        assertThat(updated.type()).isEqualTo("book"); // unchanged
    }

    @Test
    void update_setsDeadlineAndPriority() {
        Track t = repo.create("Rust Book", "book", "manual");

        Track updated = repo.update(t.id(), null, null, null, LocalDate.of(2026, 9, 1), "must", null, false);

        assertThat(updated.deadline()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(updated.priority()).isEqualTo("must");
    }

    @Test
    void update_clearDeadline_nullsBothDeadlineAndPriority() {
        Track t = repo.create("Rust Book", "book", "manual");
        repo.update(t.id(), null, null, null, LocalDate.of(2026, 9, 1), "must", null, false);

        Track cleared = repo.update(t.id(), null, null, null, null, null, null, true);

        assertThat(cleared.deadline()).isNull();
        assertThat(cleared.priority()).isNull();
    }

    @Test
    void delete_cascadesItemsAndSchedule() {
        Track t = repo.create("Rust Book", "book", "manual");
        repo.addItem(t.id(), "Ch1", null);
        repo.setSchedule(t.id(), Map.of(0, 2));

        repo.delete(t.id());

        assertThat(repo.get(t.id())).isNull();
        assertThat(repo.listItems(t.id())).isEmpty();
        assertThat(repo.getSchedule(t.id())).isEmpty();
    }

    @Test
    void listActive_excludesArchived() {
        Track active = repo.create("Active", "book", "manual");
        Track archived = repo.create("Archived", "book", "manual");
        repo.update(archived.id(), null, null, "archived", null, null, null, false);

        List<Track> result = repo.listActive();

        assertThat(result).extracting(Track::id).containsExactly(active.id());
    }

    // ── Items: ordering + reorder ───────────────────────────────────────────

    @Test
    void addItem_appendsAtNextPosition() {
        Track t = repo.create("Rust Book", "book", "manual");

        TrackItem i1 = repo.addItem(t.id(), "Ch1", null);
        TrackItem i2 = repo.addItem(t.id(), "Ch2", null);
        TrackItem i3 = repo.addItem(t.id(), "Ch3", "vault/Ch3.md");

        assertThat(i1.position()).isEqualTo(0);
        assertThat(i2.position()).isEqualTo(1);
        assertThat(i3.position()).isEqualTo(2);
        assertThat(i3.notePath()).isEqualTo("vault/Ch3.md");
        assertThat(repo.listItems(t.id())).extracting(TrackItem::title)
            .containsExactly("Ch1", "Ch2", "Ch3");
    }

    @Test
    void reorderItem_movingForward_shiftsSiblingsBack() {
        Track t = repo.create("Rust Book", "book", "manual");
        TrackItem i1 = repo.addItem(t.id(), "A", null); // pos 0
        TrackItem i2 = repo.addItem(t.id(), "B", null); // pos 1
        TrackItem i3 = repo.addItem(t.id(), "C", null); // pos 2

        repo.reorderItem(i1.id(), 2); // move A to the end

        List<TrackItem> items = repo.listItems(t.id());
        assertThat(items).extracting(TrackItem::title).containsExactly("B", "C", "A");
    }

    @Test
    void reorderItem_movingBackward_shiftsSiblingsForward() {
        Track t = repo.create("Rust Book", "book", "manual");
        TrackItem i1 = repo.addItem(t.id(), "A", null); // pos 0
        TrackItem i2 = repo.addItem(t.id(), "B", null); // pos 1
        TrackItem i3 = repo.addItem(t.id(), "C", null); // pos 2

        repo.reorderItem(i3.id(), 0); // move C to the front

        List<TrackItem> items = repo.listItems(t.id());
        assertThat(items).extracting(TrackItem::title).containsExactly("C", "A", "B");
    }

    @Test
    void completeItem_marksDoneAndIsIdempotent() {
        Track t = repo.create("Rust Book", "book", "manual");
        TrackItem item = repo.addItem(t.id(), "Ch1", null);

        boolean firstFlip = repo.completeItem(item.id());
        boolean secondFlip = repo.completeItem(item.id());

        assertThat(firstFlip).isTrue();
        assertThat(secondFlip).isFalse(); // already done — no-op
        assertThat(repo.getItem(item.id()).status()).isEqualTo("done");
        assertThat(repo.getItem(item.id()).completedAt()).isNotNull();
    }

    @Test
    void nextPendingItems_excludesDoneAndRespectsLimit() {
        Track t = repo.create("Rust Book", "book", "manual");
        TrackItem i1 = repo.addItem(t.id(), "A", null);
        repo.addItem(t.id(), "B", null);
        repo.addItem(t.id(), "C", null);
        repo.completeItem(i1.id());

        List<TrackItem> next = repo.nextPendingItems(t.id(), 1);

        assertThat(next).extracting(TrackItem::title).containsExactly("B");
        assertThat(repo.countPendingItems(t.id())).isEqualTo(2);
    }

    // ── Schedule ─────────────────────────────────────────────────────────────

    @Test
    void setSchedule_fullReplace() {
        Track t = repo.create("Rust Book", "book", "manual");
        repo.setSchedule(t.id(), Map.of(0, 2, 2, 1));

        assertThat(repo.getScheduleBudget(t.id(), 0)).isEqualTo(2);
        assertThat(repo.getScheduleBudget(t.id(), 2)).isEqualTo(1);
        assertThat(repo.getScheduleBudget(t.id(), 1)).isNull();

        // Full replace — Monday dropped, Wednesday budget changed.
        repo.setSchedule(t.id(), Map.of(2, 5));

        assertThat(repo.getScheduleBudget(t.id(), 0)).isNull();
        assertThat(repo.getScheduleBudget(t.id(), 2)).isEqualTo(5);
    }

    // ── Capacity (Phase 1c) ──────────────────────────────────────────────────

    @Test
    void capacity_seedsDefaultMonFri4SatSun6() {
        Map<Integer, Double> capacity = repo.getCapacityMap();

        for (int weekday = 0; weekday <= 4; weekday++) {
            assertThat(capacity.get(weekday)).isEqualTo(4.0);
        }
        assertThat(capacity.get(5)).isEqualTo(6.0); // Sat
        assertThat(capacity.get(6)).isEqualTo(6.0); // Sun
    }

    @Test
    void setCapacity_partialUpdate_onlyChangesGivenWeekday() {
        // daily_capacity is seeded once at context startup and NOT truncated by cleanTables()
        // (it's not per-track state) — restore Wednesday afterward so this test's order
        // relative to capacity_seedsDefaultMonFri4SatSun6 doesn't matter.
        try {
            repo.setCapacity(Map.of(2, 9.0)); // Wednesday only

            assertThat(repo.getCapacity(2)).isEqualTo(9.0);
            assertThat(repo.getCapacity(0)).isEqualTo(4.0); // Monday untouched
            assertThat(repo.getCapacity(5)).isEqualTo(6.0); // Saturday untouched
        } finally {
            repo.setCapacity(Map.of(2, 4.0));
        }
    }

    // ── Progress (Phase 1d) ──────────────────────────────────────────────────

    @Test
    void capacityBetween_sumsSeedShape_monToSat() {
        // default seed: weekday=4, Sat/Sun=6. Mon..Sat exclusive = Mon,Tue,Wed,Thu,Fri = 5*4=20
        double sum = repo.capacityBetween(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 22));
        assertThat(sum).isEqualTo(20.0);
    }

    @Test
    void listProgressRows_countsDoneAndTotal_excludesIncludeInProgressFalse() {
        Track counted = repo.create("Counted", "book", "manual");
        TrackItem i1 = repo.addItem(counted.id(), "A", null);
        repo.addItem(counted.id(), "B", null);
        repo.completeItem(i1.id());

        Track excluded = repo.create("Excluded", "book", "manual");
        TrackItem e1 = repo.addItem(excluded.id(), "A", null);
        repo.completeItem(e1.id());
        repo.update(excluded.id(), null, null, null, null, null, false, false); // include_in_progress=false

        List<TrackProgressRow> rows = repo.listProgressRows();

        assertThat(rows).extracting(r -> r.track().id()).containsExactly(counted.id());
        TrackProgressRow row = rows.get(0);
        assertThat(row.itemsDone()).isEqualTo(1);
        assertThat(row.itemsTotal()).isEqualTo(2);
    }

    @Test
    void listProgressRows_excludesArchivedTracks() {
        Track archived = repo.create("Archived", "book", "manual");
        repo.addItem(archived.id(), "A", null);
        repo.update(archived.id(), null, null, "archived", null, null, null, false);

        assertThat(repo.listProgressRows()).isEmpty();
    }

    // ── Subscriptions schema (Step 1) ───────────────────────────────────────

    @Test
    void subscriptionColumns_defaultNull_andGroupIdDefaultsNull() {
        Track t = repo.create("Rust Book", "book", "manual");
        TrackItem item = repo.addItem(t.id(), "Ch1", null);

        assertThat(t.sourceUrl()).isNull();
        assertThat(t.sourceType()).isNull();
        assertThat(t.lastCheckedAt()).isNull();
        assertThat(item.groupId()).isNull();
    }

    @Test
    void getOrCreateGroup_calledTwiceForSameCapture_returnsSameGroup() {
        Track t = repo.create("Rust Book", "book", "manual");

        TrackItemGroup first = repo.getOrCreateGroup(t.id(), "cap-1", "Video Title", "https://example.com/v1");
        TrackItemGroup second = repo.getOrCreateGroup(t.id(), "cap-1", "Video Title", "https://example.com/v1");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.title()).isEqualTo("Video Title");
        assertThat(second.sourceUrl()).isEqualTo("https://example.com/v1");
    }

    @Test
    void addItem_fourArg_withGroupId_readsBackGroupId() {
        Track t = repo.create("Rust Book", "book", "manual");
        TrackItemGroup group = repo.getOrCreateGroup(t.id(), "cap-2", "Video Title", null);

        TrackItem item = repo.addItem(t.id(), "Chapter 1", "vault/Ch1.md", group.id());

        assertThat(repo.getItem(item.id()).groupId()).isEqualTo(group.id());
    }

    @Test
    void addItem_threeArg_stillDelegatesToNullGroup() {
        Track t = repo.create("Rust Book", "book", "manual");

        TrackItem item = repo.addItem(t.id(), "Chapter 1", "vault/Ch1.md");

        assertThat(repo.getItem(item.id()).groupId()).isNull();
    }

    @Test
    void reorderItem_ignoresGroupId_positionMathUnaffected() {
        Track t = repo.create("Rust Book", "book", "manual");
        TrackItemGroup group = repo.getOrCreateGroup(t.id(), "cap-3", "Video Title", null);
        TrackItem i1 = repo.addItem(t.id(), "A", null, group.id()); // pos 0, grouped
        TrackItem i2 = repo.addItem(t.id(), "B", null, null);       // pos 1, ungrouped
        TrackItem i3 = repo.addItem(t.id(), "C", null, group.id()); // pos 2, grouped

        repo.reorderItem(i1.id(), 2); // move grouped item A past ungrouped B — same shape as
                                       // reorderItem_movingForward_shiftsSiblingsBack above

        List<TrackItem> items = repo.listItems(t.id());
        assertThat(items).extracting(TrackItem::title).containsExactly("B", "C", "A");
        assertThat(repo.getItem(i1.id()).groupId()).isEqualTo(group.id()); // survives reorder
        assertThat(repo.getItem(i2.id()).groupId()).isNull();
        assertThat(repo.getItem(i3.id()).groupId()).isEqualTo(group.id());
    }

    @Test
    void create_fiveArg_setsSourceUrlAndType() {
        Track t = repo.create("Channel", "subscription", "manual",
            "https://youtube.com/@ch", "youtube_channel");

        assertThat(t.sourceUrl()).isEqualTo("https://youtube.com/@ch");
        assertThat(t.sourceType()).isEqualTo("youtube_channel");
        assertThat(repo.get(t.id())).isEqualTo(t);
    }

    @Test
    void markChecked_setsLastCheckedAt() {
        Track t = repo.create("Channel", "subscription", "manual",
            "https://youtube.com/@ch", "youtube_channel");
        Instant when = Instant.now();

        repo.markChecked(t.id(), when);

        assertThat(repo.get(t.id()).lastCheckedAt()).isCloseTo(when, within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void initSchema_isIdempotent() {
        repo.initSchema();
        repo.initSchema();

        Track t = repo.create("Rust Book", "book", "manual");
        assertThat(repo.get(t.id())).isEqualTo(t);
    }
}
