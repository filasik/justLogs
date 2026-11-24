# Installation

## Requirements

| | |
|---|---|
| Server | **Paper** 1.21.1 or newer (also Purpur and other Paper forks). Spigot/CraftBukkit is not supported: JustLogs is a Paper plugin (`paper-plugin.yml`). |
| Java | 21 or newer (whatever your Paper version needs) |
| Internet | Needed on the **first start only**, to download the database drivers |
| Folia | Block and container rollback, restore and undo tested across independent regions on 26.3 |

## Steps

1. Download the latest `.jar` from [GitHub Releases](https://github.com/filasik/justLogs/releases) or Modrinth.
2. Put it into the `plugins/` folder of your server.
3. Start the server.
4. Check the console for the JustLogs banner. It shows the storage type, schema version, active loggers and language.
5. Optional: edit `plugins/JustLogs/config.yml` (see [Configuration](Configuration)) and run `/jo reload`, or restart for storage changes.

That's it. With the default settings, JustLogs uses a SQLite file and starts logging right away.

### Why the first start needs internet

To keep the jar small, the database libraries are not bundled. On the first start Paper's library loader downloads them from Maven Central and caches them in the server's `libraries/` folder:

- `org.xerial:sqlite-jdbc`
- `org.mariadb.jdbc:mariadb-java-client`
- `com.zaxxer:HikariCP`

Later starts use the cached copies. If your server has no internet access at all, start it once somewhere with access and copy the `libraries/` folder over.

The only library shaded into the jar is bStats (relocated).

## Files created in `plugins/JustLogs/`

| File | Purpose |
|---|---|
| `config.yml` | Settings. See [Configuration](Configuration). |
| `messages_en.yml`, `messages_cs.yml` | All texts, yours to edit. See [Messages and Translations](Messages-and-Translations). |
| `justlogs.db` | The SQLite database (only with `storage.type: sqlite`) |
| `secrets.key` | A random key used for keyed hashing. It is kept outside the database on purpose, so a database dump alone is not enough to reverse hashes. Keep it with your backups and don't share it. |
| `config.yml.bak-v<n>` | Backup made before an automatic config update |

## Updating

1. Stop the server.
2. Replace the old jar with the new one.
3. Start the server.

You don't need to touch anything else:

- **Config**: new options are added to your existing `config.yml` automatically. Your values stay, and a backup `config.yml.bak-v<n>` is saved first.
- **Database**: the schema is versioned and migrated automatically on start (the current schema version is shown in `/jo status`).
- **Messages**: missing keys fall back to the built-in text, so old message files keep working.

With `update-check: true`, JustLogs checks GitHub for new releases every 12 hours and tells players with `justlogs.notify` when they join.

## Uninstalling

Delete the jar. Your data stays in `plugins/JustLogs/` (or in your MariaDB database, tables prefixed `jl_`) until you delete it.
