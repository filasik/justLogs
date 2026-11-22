package me.skerik.justlogs.model;

/** A {@code jl_sign} row. Texts are lines joined with {@code \n}. */
public record SignEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                        int x, int y, int z, int side, String oldText, String newText,
                        boolean rolledBack) implements LogEntry {
}
