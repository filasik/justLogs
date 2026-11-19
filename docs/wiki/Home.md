# JustLogs

**JustLogs** is a block and container logging plugin for Paper servers. It records who broke, placed, burned, blew up or took what, lets staff look it up in game, and puts it back with a rollback.

- **Logs** blocks placed and broken by players, water and lava buckets, fire, explosions, tree growth, mobs changing blocks, and items put into or taken out of containers.
- **Attributes indirect damage** to the player behind it: the player who lit the fire or the TNT, not just "fire" or "tnt".
- **Inspector**: click a block or a chest to see its history.
- **Lookup** with a short query language (`u:Notch t:2d r:20 a:-block`), clickable coordinates and paging.
- **Rollback, restore and undo** by player, area and time, with a private preview (ghost blocks) and a confirmation step for big changes. Rollbacks run chunk by chunk within a per-tick time budget, so the server doesn't freeze.
- **Storage**: SQLite out of the box, or MariaDB/MySQL to share one database between several servers.
- **Fast**: records are queued in memory and written in batches by one background thread.
- **Languages**: English and Czech, every message editable (MiniMessage).

## Pages

| Page | What it covers |
|---|---|
| [Installation](Installation) | Requirements, first start, updating |
| [Configuration](Configuration) | Every option in `config.yml` |
| [Commands and Permissions](Commands-and-Permissions) | All commands, aliases and permission nodes |
| [Query Syntax](Query-Syntax) | The `u: t: r: a: b: w: at:` parameters and `#flags` |
| [Inspector and Lookup](Inspector-and-Lookup) | Finding out what happened |
| [Rollback and Restore](Rollback-and-Restore) | Putting things back, preview, confirm, undo |
| [What Gets Logged](What-Gets-Logged) | Events, sources like `#tnt`, known gaps |
| [Storage](Storage) | SQLite, MariaDB/MySQL, shared databases, files on disk |
| [Messages and Translations](Messages-and-Translations) | Editing texts, adding a language |
| [Development](Development) | Building, tests, project layout, releasing |
| [FAQ](FAQ) | Common problems and questions |

## Quick start

1. Drop `JustLogs-<version>.jar` into `plugins/` and start the server (the first start needs internet access).
2. Give your staff `justlogs.helper` (look things up) or `justlogs.admin` (everything). Operators have everything by default.
3. In game: `/jo inspect`, then left click a block.
4. Someone griefed? `/jo rollback u:Griefer t:2h r:30 #preview`, check the ghost blocks, then `/jo confirm`.

## Links

- Source and issues: <https://github.com/filasik/justLogs>
- Downloads: [GitHub Releases](https://github.com/filasik/justLogs/releases) and Modrinth
- License: MIT
