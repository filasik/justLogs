package me.skerik.justlogs.inspect;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Which players are in inspector mode. Memory only: the mode ends with the session. */
public final class Inspector {

    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    /** @return true if the mode is now on */
    public boolean toggle(UUID player) {
        if (active.remove(player)) return false;
        active.add(player);
        return true;
    }

    public boolean isInspecting(UUID player) {
        return active.contains(player);
    }

    public void remove(UUID player) {
        active.remove(player);
    }

    public int count() {
        return active.size();
    }
}
