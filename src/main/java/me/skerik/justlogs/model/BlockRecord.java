package me.skerik.justlogs.model;

/**
 * A block changed from {@code old*} to {@code new*}. Material is the lower-case registry key without namespace
 * ({@code diamond_ore}), empty for "nothing" (air counts as {@code air}); data is the block state without brackets
 * ({@code facing=north,half=top}), empty when the block has no state.
 */
public record BlockRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                          String oldMaterial, String oldData, String newMaterial, String newData) implements LogRecord {
}
