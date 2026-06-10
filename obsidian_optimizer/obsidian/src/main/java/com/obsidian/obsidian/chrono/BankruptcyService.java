package com.obsidian.obsidian.chrono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

@Component
public class BankruptcyService {

    private static final Logger log = LoggerFactory.getLogger(BankruptcyService.class);

    private static final int MIN_INTERVAL            = 2;
    private static final int MIN_EASE                = 215;
    private static final int TIER_SHORT_MAX          = 7;
    private static final int TIER_MEDIUM_MAX         = 30;
    private static final int TIER_LONG_MAX           = 90;
    private static final int TIER_LONG_INTERVAL      = 21;
    private static final int TIER_VERY_LONG_INTERVAL = 45;

    private static final Random RANDOM = new Random();

    public record BankruptcyResult(int overdueCount, boolean declared, int rescheduled) {}

    public BankruptcyResult run(List<Path> mdFiles, int bankruptcyLimit) {
        LocalDate today = LocalDate.now();
        List<Path> overdue = new ArrayList<>();

        for (Path file : mdFiles) {
            FrontmatterRewriter.SrFields fields = FrontmatterRewriter.read(file);
            if (fields != null && fields.due().isBefore(today)) {
                overdue.add(file);
            }
        }

        log.info("[BankruptcyCheck] {} overdue note(s) found.", overdue.size());

        if (overdue.size() < bankruptcyLimit) {
            log.info("[BankruptcyCheck] No bankruptcy — below threshold of {}.", bankruptcyLimit);
            return new BankruptcyResult(overdue.size(), false, 0);
        }

        log.info("[BankruptcyCheck] BANKRUPTCY DECLARED. Spreading relief across {} files.", overdue.size());
        PriorityQueue<int[]> heapLong     = buildHeap(TIER_LONG_INTERVAL);
        PriorityQueue<int[]> heapVeryLong = buildHeap(TIER_VERY_LONG_INTERVAL);
        Map<LocalDate, Integer> dayLoad   = new HashMap<>();
        int rescheduled = 0;

        for (Path file : overdue) {
            try {
                applyBankruptcy(file, today, heapLong, heapVeryLong, dayLoad);
                rescheduled++;
            } catch (IOException e) {
                log.warn("[BankruptcyCheck] Failed to process {}: {}", file.getFileName(), e.getMessage());
            }
        }

        return new BankruptcyResult(overdue.size(), true, rescheduled);
    }

    private void applyBankruptcy(Path file, LocalDate today,
            PriorityQueue<int[]> heapLong, PriorityQueue<int[]> heapVeryLong,
            Map<LocalDate, Integer> dayLoad) throws IOException {
        FrontmatterRewriter.SrFields current = FrontmatterRewriter.read(file);
        if (current == null) return;

        int newInterval = tieredInterval(current.interval());
        int newEase     = Math.max(MIN_EASE, current.ease() / 2);

        LocalDate newDue;
        if (newInterval == TIER_LONG_INTERVAL) {
            newDue = pickFromHeap(today, heapLong);
        } else if (newInterval == TIER_VERY_LONG_INTERVAL) {
            newDue = pickFromHeap(today, heapVeryLong);
        } else {
            newDue = pickDate(today, newInterval, dayLoad);
        }

        FrontmatterRewriter.write(file, new FrontmatterRewriter.SrFields(newDue, newInterval, newEase));
    }

    private static int tieredInterval(int current) {
        if (current <= TIER_SHORT_MAX)  return MIN_INTERVAL;
        if (current <= TIER_MEDIUM_MAX) return Math.max(MIN_INTERVAL, current / 2);
        if (current <= TIER_LONG_MAX)   return TIER_LONG_INTERVAL;
        return TIER_VERY_LONG_INTERVAL;
    }

    private static PriorityQueue<int[]> buildHeap(int slots) {
        PriorityQueue<int[]> heap = new PriorityQueue<>(
            (a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]
        );
        for (int d = 1; d <= slots; d++) {
            heap.offer(new int[]{0, RANDOM.nextInt(Integer.MAX_VALUE), d});
        }
        return heap;
    }

    private static LocalDate pickFromHeap(LocalDate today, PriorityQueue<int[]> heap) {
        int[] slot = heap.poll();
        slot[0]++;
        slot[1] = RANDOM.nextInt(Integer.MAX_VALUE);
        heap.offer(slot);
        return today.plusDays(slot[2]);
    }

    private static LocalDate pickDate(LocalDate today, int interval, Map<LocalDate, Integer> dayLoad) {
        int minLoad = Integer.MAX_VALUE;
        List<LocalDate> candidates = new ArrayList<>();
        for (int d = 1; d <= interval; d++) {
            LocalDate candidate = today.plusDays(d);
            int load = dayLoad.getOrDefault(candidate, 0);
            if (load < minLoad) {
                minLoad = load;
                candidates.clear();
                candidates.add(candidate);
            } else if (load == minLoad) {
                candidates.add(candidate);
            }
        }
        LocalDate chosen = candidates.get(RANDOM.nextInt(candidates.size()));
        dayLoad.merge(chosen, 1, Integer::sum);
        return chosen;
    }
}
