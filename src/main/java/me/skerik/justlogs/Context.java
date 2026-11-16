package me.skerik.justlogs;

import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.config.Settings;
import me.skerik.justlogs.storage.PlayerStore;
import java.util.logging.Logger;

/**
 * Everything commands and listeners need, built once startup has finished (so it is null before that and
 * the plugin answers "not ready"). Settings and messages are swapped atomically on /justlogs reload.
 */
public final class Context {

    public final JustLogs plugin;
    public final Scheduler scheduler;
    public final Logger log;
    public final PlayerStore players;

    private volatile Settings settings;
    private volatile Messages messages;

    public Context(JustLogs plugin, Scheduler scheduler, Settings settings, Messages messages
            , PlayerStore players
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.log = plugin.getLogger();
        this.players = players;
        update(settings, messages);
    }

    public void update(Settings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }
}
