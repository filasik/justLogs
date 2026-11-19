# Configuration

The file is `plugins/JustLogs/config.yml`. Every option is commented in the file too.

Most values reload with `/jo reload`. **Storage settings, queue sizes and command aliases need a restart.**

## General

| Option | Default | Description |
|---|---|---|
| `config-version` | `4` | Format version. **Don't change it.** It drives the automatic config update. |
| `locale` | `en` | Language of player-facing messages: `en` or `cs` (or your own, see [Messages and Translations](Messages-and-Translations)). |
| `update-check` | `true` | Check GitHub releases for a newer version at startup and every 12 hours. Players with `justlogs.notify` are told when they join. |
| `command.aliases` | `[jo, jlogs]` | Short names for `/justlogs`. Restart required. Remove one if another plugin already owns it. |
| `server-name` | `""` | Name of this server inside a shared database. Leave empty on a single server. See [Storage](Storage#sharing-one-database-between-servers). |
| `metrics` | `true` | Anonymous bStats statistics. `false` switches them off for JustLogs; the global switch is `plugins/bStats/config.yml`. |

## `logging`: what gets recorded

A disabled logger costs nothing. See [What Gets Logged](What-Gets-Logged) for details.

| Option | Default | Description |
|---|---|---|
| `block-place` | `true` | Blocks placed by players |
| `block-break` | `true` | Blocks broken by players |
| `buckets` | `true` | Water and lava buckets emptied and filled |
| `fire` | `true` | Ignited and burned blocks, attributed to the player who lit the fire (else `#fire`, `#lava`, `#lightning`) |
| `explosions` | `true` | TNT, creepers, beds and so on. TNT is attributed to the player who lit it (else `#tnt`). |
| `growth` | `true` | Trees and mushrooms growing (the bone meal player, else `#nature`) |
| `leaf-decay` | `false` | Leaves rotting away. Very noisy, so off by default. |
| `entity-changes` | `true` | Endermen, farmland trampling, silverfish and other mobs changing blocks |
| `containers` | `true` | Items put into and taken out of chests, barrels, shulker boxes, hoppers, furnaces and so on. Broken chests also keep their contents so a rollback can put them back. |
| `worlds-blacklist` | `[]` | Worlds that are never logged (exact names) |

## `queue`: writing to the database

Records are collected in memory and written in batches by one background thread.

| Option | Default | Description |
|---|---|---|
| `flush-interval-ms` | `250` | How often the writer flushes |
| `batch-size` | `2000` | Rows per batch |
| `max-size` | `500000` | When the database can't keep up, low-value records (fluid flow, clicks) are dropped at this size and everything at twice this size. `/jo status` shows the dropped count. |
| `shutdown-timeout-seconds` | `30` | On shutdown the remaining records are written, for at most this long |

## `lookup`

| Option | Default | Description |
|---|---|---|
| `page-size` | `8` | Rows per page in chat |
| `max-radius` | `100` | Largest `r:` a player without `justlogs.lookup.global` may use. Also limits rollbacks for players without `justlogs.rollback.global`. |
| `default-radius` | `10` | Radius used when a player gives no scope at all (no `u:`, `r:`, `b:` or `w:`). Also used by `/jo near` without a number. |
| `default-time` | `3d` | Time window used when a lookup has no `t:`. Rollbacks never use it: they must name their time. |
| `count-cap` | `100000` | The page counter stops counting at this many rows (shown as e.g. `12/1000+`) |
| `result-ttl-seconds` | `300` | How long `/jo page` keeps your last result |

## `rollback`

| Option | Default | Description |
|---|---|---|
| `max-rows` | `500000` | Most log rows one rollback or restore may load. Narrow the query (`u:`, `r:`, `t:`) if you hit it. |
| `confirm-above` | `10000` | A rollback or restore changing more blocks than this waits for `/jo confirm`. `0` means always ask. |
| `confirm-expire-seconds` | `60` | How long a pending preview or confirmation stays valid |
| `ms-per-tick` | `8` | Time budget per server tick while changing blocks. A big rollback takes several ticks instead of freezing the server. |
| `preview-max-blocks` | `50000` | Largest preview. Bigger rollbacks need `/jo confirm` without `#preview`. |

## `storage`

Restart required after changes. See [Storage](Storage) for more.

| Option | Default | Description |
|---|---|---|
| `type` | `sqlite` | `sqlite` (a single file, zero setup) or `mariadb` (MariaDB or MySQL server) |
| `sqlite.file` | `justlogs.db` | File in the plugin folder |
| `sqlite.pool-size` | `3` | Concurrent connections (SQLite serializes writes anyway) |
| `mariadb.host` | `localhost` | |
| `mariadb.port` | `3306` | |
| `mariadb.database` | `justlogs` | Must exist; tables are created automatically |
| `mariadb.user` | `justlogs` | |
| `mariadb.password` | `change-me` | |
| `mariadb.pool-size` | `6` | |
| `mariadb.ssl-mode` | `""` | TLS mode. Empty uses the driver default. `trust` (encrypted, certificate not verified), `disable`, `verify-ca`, `verify-full`. See [FAQ](FAQ#mysql-8-cannot-use-authentication-plugin-caching_sha2_password). |

## Full default file

```yaml
config-version: 4
locale: en
update-check: true
command:
  aliases: [jo, jlogs]
server-name: ""
metrics: true

logging:
  block-place: true
  block-break: true
  buckets: true
  fire: true
  explosions: true
  growth: true
  leaf-decay: false
  entity-changes: true
  containers: true
  worlds-blacklist: []

queue:
  flush-interval-ms: 250
  batch-size: 2000
  max-size: 500000
  shutdown-timeout-seconds: 30

lookup:
  page-size: 8
  max-radius: 100
  default-radius: 10
  default-time: 3d
  count-cap: 100000
  result-ttl-seconds: 300

rollback:
  max-rows: 500000
  confirm-above: 10000
  confirm-expire-seconds: 60
  ms-per-tick: 8
  preview-max-blocks: 50000

storage:
  type: sqlite
  sqlite:
    file: justlogs.db
    pool-size: 3
  mariadb:
    host: localhost
    port: 3306
    database: justlogs
    user: justlogs
    password: change-me
    pool-size: 6
    ssl-mode: ""
```

## Automatic config updates

When a new version adds options, JustLogs raises `config-version`, adds the new keys with their comments to your file, keeps your values, and saves a backup as `config.yml.bak-v<old version>` first.
