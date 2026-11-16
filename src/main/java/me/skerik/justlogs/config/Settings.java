package me.skerik.justlogs.config;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import me.skerik.justlogs.storage.DbConfig;
import java.nio.file.Path;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml. Rules: every option is read here and nowhere else, every value is validated,
 * an invalid value falls back to the default and is logged (the plugin never refuses to start over a typo).
 */
public record Settings(
        String locale, boolean updateCheck, boolean metrics, List<String> aliases, boolean welcome
        , Storage storage
) {

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
                c.getBoolean("welcome.enabled", true)
                , storage(c, log)
        );
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
}
