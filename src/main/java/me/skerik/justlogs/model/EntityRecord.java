package me.skerik.justlogs.model;

import org.jspecify.annotations.Nullable;

/** An entity kill, player death, hanging/vehicle break, tame, shear, leash or bucket capture. */
public record EntityRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                           String entityType, @Nullable Actor victim, int cause,
                           @Nullable String name, @Nullable String detail) implements LogRecord {
}
