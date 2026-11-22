package me.skerik.justlogs.model;

import org.jspecify.annotations.Nullable;

/** A {@code jl_entity} row (kill, player-death, hanging, vehicle, tame, shear, leash, bucket-entity). */
public record EntityEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                          int x, int y, int z, String entityType, @Nullable String victim,
                          @Nullable String name, @Nullable String detail, boolean rolledBack) implements LogEntry {
}
