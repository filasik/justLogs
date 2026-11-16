package me.skerik.justlogs.model;

import org.jspecify.annotations.Nullable;

/**
 * A block changed from {@code old*} to {@code new*}. Material is the lower-case registry key without namespace
 * ({@code diamond_ore}), empty for "nothing" (air counts as {@code air}); data is the block state without brackets
 * ({@code facing=north,half=top}), empty when the block has no state. {@code oldNbt} holds the contents of the block's
 * tile entity before the change (items of a chest), null for blocks without any.
 */
public record BlockRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                          String oldMaterial, String oldData, String newMaterial, String newData,
                          byte @Nullable [] oldNbt) implements LogRecord {

    public BlockRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                       String oldMaterial, String oldData, String newMaterial, String newData) {
        this(time, actor, action, world, x, y, z, oldMaterial, oldData, newMaterial, newData, null);
    }
}
