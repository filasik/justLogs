package me.skerik.justlogs.model;

public record BlockEntry(long id, int time, String user, boolean userIsPlayer, Action action, String world,
                         int x, int y, int z, String oldMaterial, String oldData, String newMaterial, String newData,
                         boolean rolledBack) implements LogEntry {
}
