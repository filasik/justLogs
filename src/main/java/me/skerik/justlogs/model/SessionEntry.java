package me.skerik.justlogs.model;

import org.jspecify.annotations.Nullable;

/** A {@code jl_session} row (login / logout). */
public record SessionEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                           int x, int y, int z, @Nullable String ipHash, @Nullable String ip) implements LogEntry {

    @Override
    public boolean rolledBack() {
        return false;
    }
}
