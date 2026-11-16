package me.skerik.justlogs.rollback;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import me.skerik.justlogs.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Runs one job per chunk, one after the other, each on the thread that owns the chunk (Folia: its region). After a chunk
 * the time spent decides whether the next one follows at once or on the next tick, which keeps a big rollback inside
 * {@code rollback.ms-per-tick}.
 */
final class ChunkRunner {

    /** The work for one chunk; runs on the chunk's thread. */
    interface Work {
        void run(World world, AtomicInteger changed, AtomicInteger skipped);
    }

    record Job(String world, int chunkX, int chunkZ, int size, Work work) {
    }

    private final Scheduler scheduler;
    private final int msPerTick;
    private final List<Job> jobs;
    private final AtomicInteger changed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger chunksDone = new AtomicInteger();
    private final CompletableFuture<ApplyResult> done = new CompletableFuture<>();

    private ChunkRunner(Scheduler scheduler, int msPerTick, List<Job> jobs) {
        this.scheduler = scheduler;
        this.msPerTick = msPerTick;
        this.jobs = jobs;
    }

    static CompletableFuture<ApplyResult> run(Scheduler scheduler, int msPerTick, List<Job> jobs) {
        ChunkRunner runner = new ChunkRunner(scheduler, msPerTick, jobs);
        runner.step(0, 0L);
        return runner.done;
    }

    private void step(int index, long usedNanos) {
        if (index >= jobs.size()) {
            done.complete(new ApplyResult(changed.get(), skipped.get(), chunksDone.get()));
            return;
        }
        Job job = jobs.get(index);
        World world = Bukkit.getWorld(job.world());
        if (world == null) {
            skipped.addAndGet(job.size());
            step(index + 1, usedNanos);
            return;
        }
        world.getChunkAtAsync(job.chunkX(), job.chunkZ()).whenComplete((loaded, error) -> {
            if (error != null || loaded == null) {
                skipped.addAndGet(job.size());
                next(index, usedNanos);
                return;
            }
            scheduler.runAtRegion(world, job.chunkX(), job.chunkZ(), () -> {
                long started = System.nanoTime();
                try {
                    job.work().run(world, changed, skipped);
                    chunksDone.incrementAndGet();
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                    return;
                }
                next(index, usedNanos + (System.nanoTime() - started));
            });
        });
    }

    private void next(int index, long usedNanos) {
        if (usedNanos >= msPerTick * 1_000_000L) scheduler.runLater(() -> step(index + 1, 0L), 1L);
        else step(index + 1, usedNanos);
    }
}
