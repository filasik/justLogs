package me.skerik.justlogs.model;

import org.jspecify.annotations.Nullable;

/** An item dropped, picked up or crafted by a player. */
public record ItemRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                         String material, int amount, byte @Nullable [] itemData) implements LogRecord {
}
