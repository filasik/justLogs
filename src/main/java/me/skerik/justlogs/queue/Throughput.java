package me.skerik.justlogs.queue;

import java.util.concurrent.atomic.AtomicLongArray;

/** Rows written per second, averaged over the last ten seconds. */
public final class Throughput {

    private static final int WINDOW = 10;

    private final AtomicLongArray counts = new AtomicLongArray(WINDOW);
    private final AtomicLongArray stamps = new AtomicLongArray(WINDOW);

    public void record(long rows, long nowMillis) {
        long second = nowMillis / 1000;
        int slot = (int) (second % WINDOW);
        if (stamps.get(slot) != second) {
            stamps.set(slot, second);
            counts.set(slot, 0);
        }
        counts.addAndGet(slot, rows);
    }

    public double perSecond(long nowMillis) {
        long second = nowMillis / 1000;
        long total = 0;
        for (int i = 0; i < WINDOW; i++) {
            if (second - stamps.get(i) < WINDOW) total += counts.get(i);
        }
        return total / (double) WINDOW;
    }
}
