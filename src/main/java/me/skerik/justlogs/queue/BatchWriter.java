package me.skerik.justlogs.queue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.model.ContainerRecord;
import me.skerik.justlogs.model.LogRecord;
import me.skerik.justlogs.storage.BlockStore;
import me.skerik.justlogs.storage.ContainerStore;
import me.skerik.justlogs.storage.Database;
import me.skerik.justlogs.storage.IdCache;
import org.jspecify.annotations.Nullable;

/**
 * The only thread that writes log rows. Drains the {@link LogQueue} every flush interval (or at once while it is
 * non-empty), resolves dictionary ids, and inserts each batch in one transaction on its own connection. When the
 * database is down the batch stays in memory and the write is retried with an exponential back-off (1 s up to 60 s);
 * the queue limits protect memory in the meantime.
 */
public final class BatchWriter {

    private record Waiter(long marker, CompletableFuture<Void> future) {
    }

    private final LogQueue queue;
    private final Database db;
    private final IdCache ids;
    private final BlockStore blocks;
    private final ContainerStore containers = new ContainerStore();
    private final @Nullable Spool spool;
    private final Logger log;
    private final Throughput throughput = new Throughput();
    private final ConcurrentLinkedQueue<Waiter> waiters = new ConcurrentLinkedQueue<>();
    private final AtomicLong written = new AtomicLong();
    private volatile int flushIntervalMs;
    private volatile int batchSize;
    private volatile boolean running;
    private volatile boolean drainOnStop;
    private volatile long lastFlushMillis;
    private volatile @Nullable String lastError;
    private @Nullable Thread thread;

    public BatchWriter(LogQueue queue, Database db, IdCache ids, BlockStore blocks, int flushIntervalMs, int batchSize,
                       Logger log) {
        this(queue, db, ids, blocks, null, flushIntervalMs, batchSize, log);
    }

    public BatchWriter(LogQueue queue, Database db, IdCache ids, BlockStore blocks, @Nullable Spool spool,
                       int flushIntervalMs, int batchSize, Logger log) {
        this.queue = queue;
        this.db = db;
        this.ids = ids;
        this.blocks = blocks;
        this.spool = spool;
        this.flushIntervalMs = flushIntervalMs;
        this.batchSize = batchSize;
        this.log = log;
    }

    public void configure(int flushIntervalMs, int batchSize) {
        this.flushIntervalMs = flushIntervalMs;
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "JustLogs-Writer");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /**
     * Completes when everything offered before this call has been written. Lookups, inspection and rollbacks wait for
     * this so an admin sees a block broken 100 ms ago.
     */
    public CompletableFuture<Void> flushUpTo(long marker) {
        if (written.get() >= marker) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> future = new CompletableFuture<>();
        waiters.add(new Waiter(marker, future));
        wake();
        return future;
    }

    public CompletableFuture<Void> flushAll() {
        return flushUpTo(queue.accepted());
    }

    /**
     * Stops the writer. With {@code drain} the remaining queue is written first, but never longer than the timeout.
     *
     * @return records that could not be written
     */
    public synchronized long stop(boolean drain, long timeoutMillis) {
        Thread t = thread;
        if (t == null) return queue.size();
        drainOnStop = drain;
        running = false;
        LockSupport.unpark(t);
        try {
            t.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        if (drain && spool != null) drainQueueToSpool();
        failWaiters();
        return queue.size();
    }

    public long spooledCount() {
        return spool == null ? 0 : spool.pendingCount();
    }

    public Throughput throughput() {
        return throughput;
    }

    public long lastFlushMillis() {
        return lastFlushMillis;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    public long written() {
        return written.get();
    }

    private void wake() {
        Thread t = thread;
        if (t != null) LockSupport.unpark(t);
    }

    // ── writer thread ──────────────────────────────────────────────────────────────

    private void loop() {
        Connection conn = null;
        List<LogRecord> pending = new ArrayList<>();
        long backoffMs = 1000;
        try {
            while (running || (drainOnStop && (!pending.isEmpty() || queue.size() > 0))) {
                Spool.Batch spooled = null;
                if (pending.isEmpty()) {
                    if (spool != null && spool.hasPending()) {
                        spooled = spool.peek().orElse(null);
                    }
                    if (spooled == null) queue.drainTo(pending, batchSize);
                }
                List<LogRecord> batch = spooled != null ? spooled.records() : pending;
                if (batch.isEmpty()) {
                    completeWaiters();
                    if (!running) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(flushIntervalMs));
                    continue;
                }
                try {
                    if (conn == null) {
                        conn = db.openDedicated();
                        ids.preload(conn);
                    }
                    long started = System.nanoTime();
                    write(conn, batch);
                    long now = System.currentTimeMillis();
                    throughput.record(batch.size(), now);
                    lastFlushMillis = (System.nanoTime() - started) / 1_000_000;
                    if (spooled != null) {
                        spool.ack(spooled);
                        written.addAndGet(batch.size());
                    } else {
                        written.addAndGet(pending.size());
                        pending.clear();
                    }
                    lastError = null;
                    backoffMs = 1000;
                    completeWaiters();
                } catch (SQLException | RuntimeException e) {
                    lastError = String.valueOf(e.getMessage());
                    log.log(Level.WARNING, "Cannot write " + batch.size() + " log records, retrying in "
                            + backoffMs / 1000 + " s (" + e.getMessage() + ")");
                    closeQuietly(conn);
                    conn = null;
                    if (spool != null) {
                        if (spooled == null && !pending.isEmpty() && spool.write(pending)) {
                            pending.clear();
                        }
                        drainQueueToSpool();
                    }
                    if (!running && drainOnStop) break; // shutting down against a dead database: spooled or give up
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(backoffMs));
                    backoffMs = Math.min(60_000, backoffMs * 2);
                }
                if (Thread.currentThread().isInterrupted()) break;
            }
        } finally {
            if (drainOnStop && spool != null && !pending.isEmpty() && spool.write(pending)) {
                pending.clear();
            }
            closeQuietly(conn);
        }
    }

    private void drainQueueToSpool() {
        if (spool == null) return;
        List<LogRecord> buf = new ArrayList<>(batchSize);
        while (queue.drainTo(buf, batchSize) > 0) {
            boolean ok = spool.write(buf);
            buf.clear();
            if (!ok) break;
        }
    }

    private void write(Connection conn, List<LogRecord> batch) throws SQLException {
        List<BlockRecord> blockRows = new ArrayList<>(batch.size());
        List<ContainerRecord> containerRows = new ArrayList<>();
        for (LogRecord r : batch) {
            if (r instanceof BlockRecord b) blockRows.add(b);
            else if (r instanceof ContainerRecord cr) containerRows.add(cr);
        }
        blocks.insert(conn, ids, blockRows);
        containers.insert(conn, ids, containerRows);
    }

    private void completeWaiters() {
        long done = written.get();
        // Waiters can be added slightly out of marker order by concurrent callers, so scan them all
        waiters.removeIf(w -> {
            if (w.marker() > done) return false;
            w.future().complete(null);
            return true;
        });
    }

    private void failWaiters() {
        Waiter w;
        while ((w = waiters.poll()) != null) {
            if (written.get() >= w.marker()) w.future().complete(null);
            else w.future().completeExceptionally(new IllegalStateException("JustLogs is shutting down"));
        }
    }

    private static void closeQuietly(@Nullable Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException ignored) {
            // nothing useful to do
        }
    }
}
