package me.skerik.justlogs.model;

/** A row read back from the database (lookup, inspect, API). Immutable snapshot. */
public sealed interface LogEntry permits BlockEntry, ContainerEntry {

    long id();

    int time();

    String user();

    boolean userIsPlayer();

    Action action();

    String world();

    int x();

    int y();

    int z();

    boolean rolledBack();
}
