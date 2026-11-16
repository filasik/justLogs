package me.skerik.justlogs.model;

/** A {@code jl_block} row. {@code hasNbt}: a tile entity snapshot (chest contents) is stored for it. */
public record BlockEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                         int x, int y, int z, String oldMaterial, String oldData, String newMaterial, String newData,
                         boolean rolledBack, boolean hasNbt) implements LogEntry {
}
