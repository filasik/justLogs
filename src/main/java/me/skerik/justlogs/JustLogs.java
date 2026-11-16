package me.skerik.justlogs;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import me.skerik.justlogs.command.MainCommand;
import me.skerik.justlogs.config.ConfigUpdater;
import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.config.Settings;
import me.skerik.justlogs.inspect.InspectListener;
import me.skerik.justlogs.listener.PlayerBlockListener;
import me.skerik.justlogs.queue.BatchWriter;
import me.skerik.justlogs.queue.LogQueue;
import me.skerik.justlogs.storage.BlockStore;
import me.skerik.justlogs.storage.Database;
import me.skerik.justlogs.storage.IdCache;
import me.skerik.justlogs.storage.Migrations;
import me.skerik.justlogs.util.Banner;
import me.skerik.justlogs.util.Secrets;
import me.skerik.justlogs.util.Stats;
import me.skerik.justlogs.util.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Startup order (keep it, it is the point of this class):
 * <ol>
 *   <li>{@code onEnable}, main thread, must stay fast: config (+ automatic update), settings, messages, scheduler,
 *       command registration.</li>
 *   <li>Slow or blocking work (database open, migrations, id cache, starting the writer) goes to an async task.</li>
 *   <li>{@link #finishStartup()} runs on the first tick, after every other plugin has enabled: build the
 *       {@link Context}, register the listeners (so nothing is logged before the writer exists), start timers,
 *       print the banner.</li>
 * </ol>
 * A fatal problem (database unreachable) logs one clear line and disables the plugin; it never throws a stack trace
 * into the console for something an admin has to fix.
 */
public final class JustLogs extends JavaPlugin implements Listener {

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile @Nullable Context context;
    private Scheduler scheduler;
    private @Nullable UpdateChecker updater;
    /** Cancel handles of repeating tasks; run all of them on disable. */
    private final List<Runnable> timers = new CopyOnWriteArrayList<>();
    private volatile boolean shuttingDown;
    private long enableStarted;
    private @Nullable Database database;
    private @Nullable BatchWriter writer;
    private @Nullable LogQueue queue;
    private @Nullable IdCache ids;
    private @Nullable Secrets secrets;
    private int schemaVersion;

    @Override
    public void onEnable() {
        enableStarted = System.nanoTime();
        saveDefaultConfig();
        if (ConfigUpdater.update(this) > 0) reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        messages = Messages.load(this, settings.locale());
        scheduler = new PaperScheduler(this);

        List<String> aliases = settings.aliases();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new MainCommand(this).build(), "JustLogs commands", aliases));
        getServer().getPluginManager().registerEvents(this, this);

        scheduler.runAsync(this::openDatabase);
    }

    private void openDatabase() {
        try {
            Database db = new Database(settings.storage().dbConfig(getDataFolder().toPath()));
            database = db;
            schemaVersion = Migrations.migrate(db);
            secrets = Secrets.loadOrCreate(getDataFolder().toPath().resolve("secrets.key"));
            IdCache cache = new IdCache(db.dialect(), settings.serverName());
            db.call(c -> {
                cache.preload(c);
                return null;
            });
            ids = cache;
            LogQueue q = new LogQueue(settings.queue().maxSize());
            queue = q;
            BatchWriter w = new BatchWriter(q, db, cache, new BlockStore(), settings.queue().flushIntervalMs(),
                    settings.queue().batchSize(), getLogger());
            w.start();
            writer = w;
            scheduler.runGlobal(this::finishStartup);
        } catch (SQLException | IOException | RuntimeException e) {
            String hint = String.valueOf(e.getMessage()).contains("Self signed")
                    ? " Hint: set storage.mariadb.ssl-mode to \"trust\" in config.yml (MySQL with a self-signed certificate)."
                    : "";
            getLogger().log(Level.SEVERE, "Cannot open the database: " + e.getMessage()
                    + ". Check storage settings in config.yml." + hint + " JustLogs is disabling itself.", e);
            scheduler.runGlobal(() -> Bukkit.getPluginManager().disablePlugin(this));
        }
    }

    /** Runs on the first tick: every other plugin has enabled by now. */
    private void finishStartup() {
        if (shuttingDown) return;
        Context ctx = new Context(this, scheduler, settings, messages, database, ids, queue, writer, secrets, schemaVersion);
        context = ctx;

        getServer().getPluginManager().registerEvents(new PlayerBlockListener(ctx), this);
        getServer().getPluginManager().registerEvents(new InspectListener(ctx), this);

        timers.add(scheduler.runAsyncTimer(() -> ctx.results.expire(ctx.settings().lookup().resultTtlSeconds() * 1000L),
                20L * 60, 20L * 60));
        if (settings.updateCheck()) {
            updater = new UpdateChecker(getPluginMeta().getVersion(), getLogger());
            scheduler.runAsync(updater::check);
            timers.add(scheduler.runAsyncTimer(updater::check, 12L * 60 * 60 * 20, 12L * 60 * 60 * 20));
        }
        boolean stats = Stats.start(this, settings);
        if (!stats) getLogger().fine("bStats not started (disabled in config.yml or no bStats id in this build)");

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Storage", (settings.storage().type().equals("sqlite") ? "SQLite" : "MariaDB") + " (schema v" + schemaVersion + ")");
        details.put("Logging", "blocks" + (settings.logging().buckets() ? ", buckets" : ""));
        details.put("Language", settings.locale() + "   Updates " + (settings.updateCheck() ? "on" : "off"));
        Banner.print(getComponentLogger(), getPluginMeta().getVersion(), getServer().getMinecraftVersion(), details,
                (System.nanoTime() - enableStarted) / 1_000_000);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Context ctx = context;
        UpdateChecker u = updater;
        Player player = event.getPlayer();
        if (ctx == null || u == null || u.newerVersion() == null || !player.hasPermission("justlogs.notify")) return;
        scheduler.runForEntity(player, () -> player.sendMessage(ctx.messages().get("update.available",
                Messages.text("version", String.valueOf(u.newerVersion())),
                Messages.text("url", String.valueOf(u.url())))), null);
    }

    /** /justlogs reload: config.yml values and messages. Storage settings and command aliases need a restart. */
    public void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        messages = Messages.load(this, settings.locale());
        Context ctx = context;
        if (ctx != null) ctx.update(settings, messages);
    }

    public @Nullable Context context() {
        return context;
    }

    public Messages messages() {
        return messages;
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        timers.forEach(Runnable::run);
        timers.clear();
        context = null;
        BatchWriter w = writer;
        if (w != null) {
            // Everything still queued is written before the database closes (bounded wait)
            long lost = w.stop(true, settings.queue().shutdownTimeoutSeconds() * 1000L);
            if (lost > 0) getLogger().warning(lost + " log records could not be written before shutdown");
        }
        if (database != null) database.close();
        getComponentLogger().info(net.kyori.adventure.text.Component.text("JustLogs disabled. Goodbye!",
                net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }
}
