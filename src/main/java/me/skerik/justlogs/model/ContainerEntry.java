package me.skerik.justlogs.model;

import org.jspecify.annotations.Nullable;

/** A {@code jl_container} row. {@code itemData} is only filled for rollbacks (lookups leave it null, see {@code hasData}). */
public record ContainerEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                             int x, int y, int z, String holderMaterial, String material, int amount, boolean hasData,
                             byte @Nullable [] itemData, boolean rolledBack) implements LogEntry {
}
