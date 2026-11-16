package me.skerik.justlogs.query;

import org.jspecify.annotations.Nullable;

/** Where the query is "from": the sender's position (players) or the {@code at:} coordinates; world null for the console. */
public record Origin(@Nullable String world, int x, int y, int z) {

    public static final Origin NONE = new Origin(null, 0, 0, 0);

    public boolean hasWorld() {
        return world != null;
    }
}
