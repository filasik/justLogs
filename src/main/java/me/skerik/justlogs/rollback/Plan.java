package me.skerik.justlogs.rollback;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.skerik.justlogs.Scheduler;

/** What a rollback or restore will do: block changes or container item moves. Built off-thread, applied chunk by chunk. */
public interface Plan {

    /** Number of things that will change (block positions, or item moves for containers). */
    int blocks();

    int chunkCount();

    /** Ids of every matching log row; they get the rolled-back flag set (or cleared). */
    List<Long> rowIds();

    /** True when the plan changes containers (rows of {@code jl_container}), false for blocks. */
    boolean containers();

    CompletableFuture<ApplyResult> apply(Scheduler scheduler, int msPerTick);
}
