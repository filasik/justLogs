package me.skerik.justlogs.model;

/** A {@code jl_interact} row (click / container-open). */
public record InteractEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                            int x, int y, int z, String material) implements LogEntry {

    @Override
    public boolean rolledBack() {
        return false;
    }
}
