package me.skerik.justlogs.model;

/** A {@code jl_item} row (drop / pickup / craft / death-drop). */
public record ItemEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                        int x, int y, int z, String material, int amount, boolean hasData,
                        boolean rolledBack) implements LogEntry {
}
