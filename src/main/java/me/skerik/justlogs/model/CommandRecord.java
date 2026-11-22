package me.skerik.justlogs.model;

/** A command executed by a player. */
public record CommandRecord(int time, Actor actor, String world, int x, int y, int z, String message)
        implements LogRecord {

    @Override
    public Action action() {
        return Action.COMMAND;
    }
}
