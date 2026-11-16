package me.skerik.justlogs;

import me.skerik.justlogs.command.MainCommand;
import me.skerik.justlogs.config.ConfigUpdater;
import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.config.Settings;
import me.skerik.justlogs.util.Banner;
import me.skerik.justlogs.util.Stats;
import me.skerik.justlogs.util.UpdateChecker;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import me.skerik.justlogs.storage.Database;
import me.skerik.justlogs.storage.Migrations;
import me.skerik.justlogs.storage.PlayerStore;
import java.sql.SQLException;
import java.util.logging.Level;
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
 *       command registration, listeners.</li>
 *   <li>Slow or blocking work (database open + migrations) goes to an async task, never to the main thread.</li>
 *   <li>{@link #finishStartup()} runs on the first tick, after every other plugin has enabled: look up other
 *       plugins' services here, build the {@link Context}, start timers, print the banner.</li>
 * </ol>
 * A fatal problem (database unreachable, required service missing) logs one clear line and disables the plugin;
 * it never throws a stack trace into the console for something an admin has to fix.
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
            schemaVersion = Migrations.migrate(db);
            database = db;
            scheduler.runGlobal(this::finishStartup);
        } catch (SQLException | RuntimeException e) {
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
        // Look up services of other plugins here and disable with one clear line if a required one is missing.

        context = new Context(this, scheduler, settings, messages, new PlayerStore(database));

        // Repeating tasks: scheduler.runAsyncTimer / runGlobalTimer, remember the handle in `timers`.
        if (settings.updateCheck()) {
            updater = new UpdateChecker(getPluginMeta().getVersion(), getLogger());
            scheduler.runAsync(updater::check);
            timers.add(scheduler.runAsyncTimer(updater::check, 12L * 60 * 60 * 20, 12L * 60 * 60 * 20));
        }
        boolean stats = Stats.start(this, settings);
        if (!stats) getLogger().fine("bStats not started (disabled in config.yml or no bStats id in this build)");

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Storage", (settings.storage().type().equals("sqlite") ? "SQLite" : "MariaDB") + " (schema v" + schemaVersion + ")");
        details.put("Language", settings.locale() + "   Updates " + (settings.updateCheck() ? "on" : "off"));
        Banner.print(getComponentLogger(), getPluginMeta().getVersion(), getServer().getMinecraftVersion(), details,
                (System.nanoTime() - enableStarted) / 1_000_000);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Context ctx = context;
        if (ctx == null) return;
        Player player = event.getPlayer();
        ctx.players.touch(player.getUniqueId(), player.getName(), System.currentTimeMillis()).whenComplete((v, t) -> {
            if (t != null) ctx.log.log(Level.WARNING, "Cannot save player " + player.getName(), t);
        });
        if (ctx.settings().welcome()) {
            scheduler.runForEntity(player, () -> player.sendMessage(ctx.messages().get("join.welcome",
                    Messages.text("player", player.getName()))), null);
        }
        UpdateChecker u = updater;
        if (u != null && u.newerVersion() != null && player.hasPermission("justlogs.admin")) {
            scheduler.runForEntity(player, () -> player.sendMessage(ctx.messages().get("update.available",
                    Messages.text("version", String.valueOf(u.newerVersion())),
                    Messages.text("url", String.valueOf(u.url())))), null);
        }
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
        if (database != null) database.close();
        getComponentLogger().info(net.kyori.adventure.text.Component.text("JustLogs disabled. Goodbye!",
                net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }
}
