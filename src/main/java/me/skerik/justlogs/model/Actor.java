package me.skerik.justlogs.model;

import java.util.UUID;

/**
 * Who caused a change: a player (UUID + name) or a non-player source such as {@code #tnt}, {@code #fire},
 * {@code #water}, {@code #console}. Non-player idents start with {@code '#'} and are their own display name.
 */
public record Actor(String ident, String name, boolean player) {

    public static final Actor FIRE = source("#fire");
    public static final Actor WATER = source("#water");
    public static final Actor LAVA = source("#lava");
    public static final Actor TNT = source("#tnt");
    public static final Actor CONSOLE = source("#console");

    public static Actor player(UUID uuid, String name) {
        return new Actor(uuid.toString(), name, true);
    }

    public static Actor source(String name) {
        if (!name.startsWith("#")) throw new IllegalArgumentException("source names start with #: " + name);
        return new Actor(name, name, false);
    }
}
