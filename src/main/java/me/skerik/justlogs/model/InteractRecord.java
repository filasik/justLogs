package me.skerik.justlogs.model;

/** A player interaction with a block (door, button, lever, pressure plate, ...). */
public record InteractRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                             String material) implements LogRecord {
}
