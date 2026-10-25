package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.notes.NoteIndexRepository.InboxNoteRow;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import com.obsidian.obsidian.vaults.ActiveVault;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Learn "units": one inbox group ({@code <captureId>[/<chapter>]}) = one unit of learning,
 * assigned to exactly one track (rows in {@code track_item_groups}). Assignment is LAZY — every
 * {@link #today} call assigns any unassigned inbox unit (subscription track, else General),
 * which is also the backfill for pre-existing inbox notes. See tracks/FLOWS.md "Units".
 * A unit is finished when no {@code _inbox/} notes remain for it.
 */
@Service
public class TrackUnitService {

    /** {@code .../_inbox/<captureId>/<note>.md} or {@code .../_inbox/<captureId>/<chapterSlug>/<note>.md}. */
    private static final Pattern INBOX_PATH = Pattern.compile("/_inbox/([^/]+)(?:/([^/]+))?/[^/]+$");

    private final TrackRepository trackRepo;
    private final NoteIndexRepository noteIndex;
    private final CaptureRepository captureRepo;
    private final ActiveVault activeVault;

    public TrackUnitService(TrackRepository trackRepo, NoteIndexRepository noteIndex,
                            CaptureRepository captureRepo, ActiveVault activeVault) {
        this.trackRepo = trackRepo;
        this.noteIndex = noteIndex;
        this.captureRepo = captureRepo;
        this.activeVault = activeVault;
    }

    public record Unit(String groupKey, String captureId, String chapter, String title, int noteCount,
                       long trackId, String trackTitle) {}

    public record UnitsResult(List<Unit> units, int backlogCount) {}

    /** Inbox group before track resolution; {@code order} sorts in capture order. */
    private record Group(String key, String captureId, String chapter, String title, int noteCount,
                         long createdAt, int minSeq) {}

    public static String groupKey(String captureId, String chapter) {
        return chapter == null || chapter.isEmpty() ? captureId : captureId + "/" + chapter;
    }

    /** Today's units + backlog size. Assigns unassigned units as a side effect. */
    public UnitsResult today(LocalDate date) {
        long vid = activeVault.id();
        Track general = trackRepo.getOrCreateGeneral(vid);
        List<Group> groups = inboxGroups(vid);

        Map<String, Long> assigned = trackRepo.unitAssignments(vid);
        Map<Long, Track> tracks = new HashMap<>();
        for (Track t : trackRepo.listAll(vid)) tracks.put(t.id(), t);
        for (Group g : groups) {
            if (assigned.containsKey(g.key()) || assigned.containsKey(g.captureId())) continue;
            Long target = trackRepo.subscriptionTrackId(vid, g.captureId());
            if (target == null || !tracks.containsKey(target)) target = general.id();
            trackRepo.assignUnit(vid, g.captureId(), g.chapter(), g.title(), target);
            assigned.put(g.key(), target);
        }

        Map<String, Group> byKey = new LinkedHashMap<>();
        for (Group g : groups) byKey.put(g.key(), g);
        Set<String> chosen = new LinkedHashSet<>();
        List<String> pinned = trackRepo.todayUnitKeys(vid, date);
        if (!pinned.isEmpty()) {
            for (String k : pinned) if (byKey.containsKey(k)) chosen.add(k);
        } else {
            Map<Long, Integer> perTrack = new HashMap<>();
            LocalDate prev = trackRepo.latestPlanDateBefore(vid, date);
            if (prev != null) {
                for (String k : trackRepo.todayUnitKeys(vid, prev)) {
                    if (!byKey.containsKey(k)) continue;   // finished
                    chosen.add(k);
                    perTrack.merge(trackOf(assigned, byKey.get(k), general.id()), 1, Integer::sum);
                }
            }
            int weekday = date.getDayOfWeek().getValue() - 1;
            for (Track t : trackRepo.listActive(vid)) {
                Integer budget = trackRepo.getScheduleBudget(t.id(), weekday);
                if (budget == null || budget <= 0) continue;
                for (Group g : groups) {
                    if (perTrack.getOrDefault(t.id(), 0) >= budget) break;
                    if (chosen.contains(g.key()) || trackOf(assigned, g, general.id()) != t.id()) continue;
                    chosen.add(g.key());
                    perTrack.merge(t.id(), 1, Integer::sum);
                }
            }
            if (!chosen.isEmpty()) trackRepo.saveTodayUnits(vid, date, new ArrayList<>(chosen));
        }

        List<Unit> units = new ArrayList<>();
        for (String k : chosen) {
            Group g = byKey.get(k);
            long tid = trackOf(assigned, g, general.id());
            Track t = tracks.get(tid);
            units.add(new Unit(g.key(), g.captureId(), g.chapter(), g.title(), g.noteCount(), tid,
                t == null ? "" : t.title()));
        }
        return new UnitsResult(units, groups.size() - units.size());
    }

    /** Re-home a unit. Returns the resulting Unit-less assignment, or null if the track is unknown. */
    public Track move(String captureId, String chapter, long trackId) {
        long vid = activeVault.id();
        Track t = trackRepo.get(vid, trackId);
        if (t == null) return null;
        String ch = chapter == null || chapter.isBlank() ? null : chapter;
        CaptureRepository.Capture c = captureRepo.get(captureId);
        trackRepo.assignUnit(vid, captureId, ch, c == null ? null : titleOf(c.title(), ch), trackId);
        return t;
    }

    private static long trackOf(Map<String, Long> assigned, Group g, long fallback) {
        Long t = assigned.get(g.key());
        if (t == null) t = assigned.get(g.captureId());   // legacy capture-level group row
        return t == null ? fallback : t;
    }

    private static String titleOf(String captureTitle, String chapter) {
        String base = captureTitle == null || captureTitle.isBlank() ? null : captureTitle;
        if (chapter == null) return base;
        return base == null ? chapter : base + " / " + chapter;
    }

    private List<Group> inboxGroups(long vid) {
        Map<String, int[]> agg = new LinkedHashMap<>();           // key -> {count, minSeq}
        Map<String, String[]> ids = new HashMap<>();              // key -> {captureId, chapter}
        for (InboxNoteRow r : noteIndex.listInboxCaptureNotes(vid)) {
            Matcher m = INBOX_PATH.matcher(r.path().replace('\\', '/'));
            if (!m.find() || !m.group(1).equals(r.captureId())) continue;
            String chapter = m.group(2);
            String key = groupKey(r.captureId(), chapter);
            int[] a = agg.computeIfAbsent(key, k -> new int[]{0, Integer.MAX_VALUE});
            a[0]++;
            if (r.captureSeq() != null) a[1] = Math.min(a[1], r.captureSeq());
            ids.putIfAbsent(key, new String[]{r.captureId(), chapter});
        }
        Map<String, CaptureRepository.Capture> caps = new HashMap<>();
        List<Group> out = new ArrayList<>();
        for (var e : agg.entrySet()) {
            String[] id = ids.get(e.getKey());
            CaptureRepository.Capture c = caps.computeIfAbsent(id[0], captureRepo::get);
            String title = titleOf(c == null ? null : c.title(), id[1]);
            out.add(new Group(e.getKey(), id[0], id[1], title == null ? e.getKey() : title,
                e.getValue()[0], c == null ? Long.MAX_VALUE : c.createdAt(), e.getValue()[1]));
        }
        out.sort(Comparator.comparingLong(Group::createdAt).thenComparing(Group::captureId)
            .thenComparingInt(Group::minSeq).thenComparing(g -> g.chapter() == null ? "" : g.chapter()));
        return out;
    }
}
