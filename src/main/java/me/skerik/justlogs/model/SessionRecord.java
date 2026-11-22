package me.skerik.justlogs.model;

import org.jspecify.annotations.Nullable;

/** Player join or quit event with HMAC-hashed IP (and optional raw IP). */
public record SessionRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                            @Nullable String ipHash, @Nullable String ip) implements LogRecord {
}
