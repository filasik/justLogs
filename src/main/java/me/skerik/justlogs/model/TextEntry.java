package me.skerik.justlogs.model;

/** A {@code jl_chat} or {@code jl_command} row. */
public record TextEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                        int x, int y, int z, String message) implements LogEntry {

    @Override
    public boolean rolledBack() {
        return false;
    }
}
