package me.skerik.justlogs.queue;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.skerik.justlogs.model.LogRecord;

/**
 * Unbounded-looking, lock-free hand-over from listener threads to the single writer. {@code offer} never blocks and
 * does no I/O. On Folia many region threads offer concurrently, one thread drains.
 */
public final class LogQueue {

    private final ConcurrentLinkedQueue<LogRecord> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile int softLimit;

    /** @param softLimit low-priority records are dropped at this size, all records at twice this size */
    public LogQueue(int softLimit) {
        this.softLimit = softLimit;
    }

    public void setSoftLimit(int softLimit) {
        this.softLimit = softLimit;
    }

    /** @return false if the record was dropped because the writer cannot keep up */
    public boolean offer(LogRecord record) {
        int limit = softLimit;
        int current = size.get();
        if (current >= limit * 2 || (current >= limit && record.lowPriority())) {
            dropped.incrementAndGet();
            return false;
        }
        size.incrementAndGet();
        queue.add(record);
        accepted.incrementAndGet();
        return true;
    }

    /** Moves up to {@code max} records into {@code out}; returns how many. Writer thread only. */
    public int drainTo(Collection<? super LogRecord> out, int max) {
        int n = 0;
        LogRecord r;
        while (n < max && (r = queue.poll()) != null) {
            out.add(r);
            n++;
        }
        if (n > 0) size.addAndGet(-n);
        return n;
    }

    public int size() {
        return size.get();
    }

    /** Records accepted since start; a marker for "everything offered up to now". */
    public long accepted() {
        return accepted.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public int softLimit() {
        return softLimit;
    }
}
