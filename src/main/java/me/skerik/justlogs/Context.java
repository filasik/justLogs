package me.skerik.justlogs;

import java.util.logging.Logger;
import me.skerik.justlogs.command.LookupService;
import me.skerik.justlogs.command.PurgeService;
import me.skerik.justlogs.command.ResultCache;
import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.config.Settings;
import me.skerik.justlogs.inspect.Inspector;
import me.skerik.justlogs.queue.BatchWriter;
import me.skerik.justlogs.queue.LogQueue;
import me.skerik.justlogs.rollback.RollbackService;
import me.skerik.justlogs.storage.Database;
import me.skerik.justlogs.storage.IdCache;
import me.skerik.justlogs.storage.QueryCompiler;
import me.skerik.justlogs.util.Secrets;

/**
 * Everything commands and listeners need, built once startup has finished (so it is null before that and
 * the plugin answers "not ready"). Settings and messages are swapped atomically on /justlogs reload.
 */
public final class Context {

    public final JustLogs plugin;
    public final Scheduler scheduler;
    public final Logger log;
    public final Database db;
    public final IdCache ids;
    public final LogQueue queue;
    public final BatchWriter writer;
    public final QueryCompiler compiler;
    public final Secrets secrets;
    public final Inspector inspector = new Inspector();
    public final ResultCache results = new ResultCache();
    public final LookupService lookup;
    public final RollbackService rollback;
    public final PurgeService purge;
    public final int schemaVersion;

    private volatile Settings settings;
    private volatile Messages messages;

    public Context(JustLogs plugin, Scheduler scheduler, Settings settings, Messages messages, Database db, IdCache ids,
                   LogQueue queue, BatchWriter writer, Secrets secrets, int schemaVersion) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.log = plugin.getLogger();
        this.db = db;
        this.ids = ids;
        this.queue = queue;
        this.writer = writer;
        this.compiler = new QueryCompiler(ids);
        this.secrets = secrets;
        this.schemaVersion = schemaVersion;
        update(settings, messages);
        this.lookup = new LookupService(this);
        this.rollback = new RollbackService(this);
        this.purge = new PurgeService(this);
    }

    public void update(Settings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
        queue.setSoftLimit(settings.queue().maxSize());
        writer.configure(settings.queue().flushIntervalMs(), settings.queue().batchSize());
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }
}
