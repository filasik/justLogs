# Storage

JustLogs stores its records in **SQLite** (default) or **MariaDB/MySQL**. Changing the storage needs a restart. Existing data is not copied between backends.

## SQLite (default)

```yaml
storage:
  type: sqlite
  sqlite:
    file: justlogs.db
    pool-size: 3
```

A single file in `plugins/JustLogs/`. No setup, good for most single servers. Back it up like any other file (ideally while the server is stopped).

## MariaDB / MySQL

Use this for very busy servers, or to share one database between several servers.

1. Create a database and a user:
   ```sql
   CREATE DATABASE justlogs CHARACTER SET utf8mb4;
   CREATE USER 'justlogs'@'%' IDENTIFIED BY 'a-strong-password';
   GRANT ALL PRIVILEGES ON justlogs.* TO 'justlogs'@'%';
   ```
2. Configure it:
   ```yaml
   storage:
     type: mariadb
     mariadb:
       host: localhost
       port: 3306
       database: justlogs
       user: justlogs
       password: a-strong-password
       pool-size: 6
       ssl-mode: ""
   ```
3. Restart the server. Tables are created automatically.

### `ssl-mode`

| Value | Meaning |
|---|---|
| `""` | Driver default |
| `trust` | Encrypted, certificate not verified. Use this for MySQL 8+ with its default self-signed certificate. |
| `disable` | No TLS. Only on a trusted local network. |
| `verify-ca`, `verify-full` | Encrypted and verified. Needs a proper certificate. |

If the database can't be opened, JustLogs logs one clear line explaining why and disables itself. For the MySQL self-signed certificate error it suggests `ssl-mode: "trust"`.

## Sharing one database between servers

Several servers (for example in a Velocity or BungeeCord network) can log into one MariaDB database. Give each server its own `server-name` in `config.yml`:

```yaml
server-name: "survival"
```

World names are stored per server, so `world` on `survival` and `world` on `creative` stay separate. Leave `server-name` empty on a single server.

## Schema and migrations

- All tables start with `jl_` (e.g. `jl_block`, `jl_container`, `jl_users`, `jl_rollbacks`).
- The schema is versioned. On start, JustLogs applies any missing versions automatically. `/jo status` shows the current one.
- Rows refer to players, worlds, materials and block states by small numeric ids, which keeps the tables compact.
- Rollbacks are recorded (`jl_rollbacks` with the rows they touched) so they can be undone.

## Files on disk

| File | |
|---|---|
| `justlogs.db` | SQLite database |
| `secrets.key` | A random HMAC key, kept outside the database so a database dump alone can't be used to reverse hashed values. Created with owner-only permissions where the filesystem supports it. Back it up with the database and don't share it. |

## Backups

- **SQLite**: copy `justlogs.db` and `secrets.key`.
- **MariaDB**: `mysqldump justlogs > justlogs.sql`, plus `secrets.key` from each server.
