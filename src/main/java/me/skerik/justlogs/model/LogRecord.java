package me.skerik.justlogs.model;

/**
 * An event captured by a listener, immutable and free of Bukkit types so the write path stays testable without a
 * server. Listeners only fill it with cheap values; every database id and conversion happens on the writer thread.
 */
public sealed interface LogRecord permits BlockRecord {

    /** Unix seconds. */
    int time();

    Actor actor();

    Action action();

    String world();

    int x();

    int y();

    int z();

    /**
     * Records of low value are dropped first when the queue is full (fluid flow, clicks); the rest only when it
     * is far past its limit.
     */
    default boolean lowPriority() {
        return action() == Action.FLOW || action() == Action.CLICK;
    }
}
