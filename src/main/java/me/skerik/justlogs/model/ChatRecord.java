package me.skerik.justlogs.model;

/** A player chat message. */
public record ChatRecord(int time, Actor actor, String world, int x, int y, int z, String message)
        implements LogRecord {

    @Override
    public Action action() {
        return Action.CHAT;
    }
}
