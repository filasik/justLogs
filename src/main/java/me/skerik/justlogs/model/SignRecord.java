package me.skerik.justlogs.model;

/**
 * A sign whose text was edited. {@code side}: 0 = front, 1 = back. Texts are the plain lines joined with
 * {@code \n} (empty string when all lines are blank).
 */
public record SignRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                         int side, String oldText, String newText) implements LogRecord {
}
