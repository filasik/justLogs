package me.skerik.justlogs.config;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import me.skerik.justlogs.storage.DbConfig;
import me.skerik.justlogs.util.Durations;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml. Rules: every option is read here and nowhere else, every value is validated,
 * an invalid value falls back to the default and is logged (the plugin never refuses to start over a typo).
 */
public record Settings(
        String locale, boolean updateCheck, boolean metrics, List<String> aliases, String serverName,
        Logging logging, Queue queue, Lookup lookup, Rollback rollback, Purge purge, Storage storage) {

    /** Which loggers are on. {@code worldsBlacklist} holds exact world names. */
    public record Logging(boolean blockPlace, boolean blockBreak, boolean buckets, boolean fire, boolean explosions,
                          boolean fluids, boolean fallingBlocks, boolean pistons, boolean formFade,
                          boolean growth, boolean cropGrowth, boolean leafDecay, boolean entityChanges,
                          boolean containers, boolean signs, boolean chat, boolean commands,
                          Set<String> commandBlacklist, boolean sessions, boolean logRawIp, boolean interactions,
                          boolean itemDrops, boolean itemPickups, boolean itemCrafts, boolean entityKills,
                          boolean playerDeaths, boolean hanging, boolean vehicles, Set<String> worldsBlacklist) {

        public boolean worldLogged(String world) {
            return !worldsBlacklist.contains(world);
        }
    }

    public record Queue(int flushIntervalMs, int batchSize, int maxSize, int shutdownTimeoutSeconds) {
    }

    public record Lookup(int pageSize, int maxRadius, int defaultRadius, long defaultTimeSeconds, int countCap,
                         int resultTtlSeconds) {
    }

    /** {@code maxRows}: most log rows one rollback may load; above {@code confirmAbove} blocks it asks for /jo confirm. */
    public record Rollback(int maxRows, int confirmAbove, int confirmExpireSeconds, int msPerTick, int previewMaxBlocks) {
    }

    /** Automatic retention purge ({@code retentionDays} = 0 disables automatic purging). */
    public record Purge(int retentionDays, int intervalHours) {
    }

    public record Storage(String type, String sqliteFile, int sqlitePool, String host, int port, String database,
                          String user, String password, int poolSize, String sslMode) {

        public DbConfig dbConfig(Path dataFolder) {
            if (type.equals("mariadb")) {
                return new DbConfig(DbConfig.Dialect.MARIADB, null, host, port, database, user, password, poolSize, sslMode);
            }
            return DbConfig.sqlite(dataFolder.resolve(sqliteFile), sqlitePool);
        }
    }

    public static Settings load(FileConfiguration c, Logger log) {
        return new Settings(
                c.getString("locale", "en").toLowerCase(Locale.ROOT).strip(),
                c.getBoolean("update-check", true),
                c.getBoolean("metrics", true),
                c.getStringList("command.aliases").stream().map(String::strip).filter(a -> !a.isEmpty()).toList(),
                c.getString("server-name", "").strip(),
                new Logging(c.getBoolean("logging.block-place", true), c.getBoolean("logging.block-break", true),
                        c.getBoolean("logging.buckets", true), c.getBoolean("logging.fire", true),
                        c.getBoolean("logging.explosions", true), c.getBoolean("logging.fluids", true),
                        c.getBoolean("logging.falling-blocks", true), c.getBoolean("logging.pistons", false),
                        c.getBoolean("logging.form-fade", true), c.getBoolean("logging.growth", true),
                        c.getBoolean("logging.crop-growth", false), c.getBoolean("logging.leaf-decay", false),
                        c.getBoolean("logging.entity-changes", true), c.getBoolean("logging.containers", true),
                        c.getBoolean("logging.signs", true), c.getBoolean("logging.chat", true),
                        c.getBoolean("logging.commands", true), normalizeCommands(c.getStringList("logging.command-blacklist")),
                        c.getBoolean("logging.sessions", true), c.getBoolean("logging.log-raw-ip", false),
                        c.getBoolean("logging.interactions", true), c.getBoolean("logging.item-drops", true),
                        c.getBoolean("logging.item-pickups", false), c.getBoolean("logging.item-crafts", false),
                        c.getBoolean("logging.entity-kills", true), c.getBoolean("logging.player-deaths", true),
                        c.getBoolean("logging.hanging", true), c.getBoolean("logging.vehicles", true),
                        new HashSet<>(c.getStringList("logging.worlds-blacklist"))),
                new Queue(bounded(c, "queue.flush-interval-ms", 50, 10_000, 250, log),
                        bounded(c, "queue.batch-size", 100, 50_000, 2000, log),
                        bounded(c, "queue.max-size", 10_000, 10_000_000, 500_000, log),
                        bounded(c, "queue.shutdown-timeout-seconds", 1, 600, 30, log)),
                new Lookup(bounded(c, "lookup.page-size", 1, 50, 8, log),
                        bounded(c, "lookup.max-radius", 1, 100_000, 100, log),
                        bounded(c, "lookup.default-radius", 1, 100_000, 10, log),
                        duration(c, "lookup.default-time", "3d", log),
                        bounded(c, "lookup.count-cap", 100, 10_000_000, 100_000, log),
                        bounded(c, "lookup.result-ttl-seconds", 30, 3600, 300, log)),
                new Rollback(bounded(c, "rollback.max-rows", 1000, 10_000_000, 500_000, log),
                        bounded(c, "rollback.confirm-above", 0, 10_000_000, 10_000, log),
                        bounded(c, "rollback.confirm-expire-seconds", 10, 600, 60, log),
                        bounded(c, "rollback.ms-per-tick", 1, 40, 8, log),
                        bounded(c, "rollback.preview-max-blocks", 100, 1_000_000, 50_000, log)),
                new Purge(bounded(c, "purge.retention-days", 0, 3650, 0, log),
                        bounded(c, "purge.interval-hours", 1, 168, 24, log)),
                storage(c, log));
    }

    private static Set<String> normalizeCommands(List<String> raw) {
        Set<String> out = new HashSet<>();
        for (String s : raw) {
            String clean = s.strip().toLowerCase(Locale.ROOT);
            if (clean.startsWith("/")) clean = clean.substring(1);
            if (!clean.isEmpty()) out.add(clean);
        }
        return Set.copyOf(out);
    }

    private static Storage storage(FileConfiguration c, Logger log) {
        String type = c.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT).strip();
        if (type.equals("mysql")) type = "mariadb"; // same driver and dialect
        if (!type.equals("sqlite") && !type.equals("mariadb")) {
            log.warning("storage.type must be sqlite or mariadb; using sqlite");
            type = "sqlite";
        }
        ConfigurationSection m = c.getConfigurationSection("storage.mariadb");
        return new Storage(type,
                c.getString("storage.sqlite.file", "justlogs.db"),
                bounded(c, "storage.sqlite.pool-size", 1, 32, 3, log),
                m == null ? "localhost" : m.getString("host", "localhost"),
                m == null ? 3306 : bounded(c, "storage.mariadb.port", 1, 65535, 3306, log),
                m == null ? "justlogs" : m.getString("database", "justlogs"),
                m == null ? "justlogs" : m.getString("user", "justlogs"),
                m == null ? "" : m.getString("password", ""),
                m == null ? 6 : bounded(c, "storage.mariadb.pool-size", 1, 64, 6, log),
                m == null ? "" : m.getString("ssl-mode", "").strip());
    }

    /** An int option inside [min, max]; anything else is logged and replaced by the default. */
    static int bounded(FileConfiguration c, String path, int min, int max, int fallback, Logger log) {
        int value = c.getInt(path, fallback);
        if (value < min || value > max) {
            log.warning(path + " must be between " + min + " and " + max + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    /** A duration like {@code 3d} in seconds; invalid text falls back to the default. */
    static long duration(FileConfiguration c, String path, String fallback, Logger log) {
        String text = c.getString(path, fallback);
        return Durations.parseSeconds(text == null ? fallback : text).orElseGet(() -> {
            log.warning(path + " must look like 30m, 12h or 3d; using " + fallback);
            return Durations.parseSeconds(fallback).orElse(259_200);
        });
    }
}
