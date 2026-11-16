# Changelog

## 0.2.0 - 2026-11-16
- Logs blocks placed and broken by players and water/lava buckets (queued in memory, written in batches by one thread).
- `/justlogs` (`/jo`, `/jlogs`): `inspect`, `lookup`, `near`, `page`, `tp`, `status`, `reload`, `help` with a free-form
  query (`u: t: r: a: b: w: at:` and `#count`, `#verbose`), tab completion, clickable positions and paging.
- SQLite (default) and MariaDB/MySQL storage with a versioned schema; English and Czech messages.
- `rollback` (`rb`), `restore` (`rs`), `undo`, `confirm`, `cancel`: put blocks back by player, area and time. Needs `t:` and `u:` or `r:`,
  `#preview` shows the result to you only (ghost blocks), big changes wait for `/jo confirm`, `/jo undo` reverses the last one.
  Blocks are changed chunk by chunk within a per-tick time budget. New permissions `justlogs.rollback`, `justlogs.restore`,
  `justlogs.rollback.global`, new `rollback:` section in config.yml (config-version 2, existing configs are updated automatically).
- Logs fire (ignite, burn), explosions, tree growth and block changes by mobs, attributed to the player who lit the fire or TNT
  (otherwise `#fire`, `#tnt`, `#creeper`, `#nature`). New `logging:` switches, config-version 3.
- Container logging: items put into and taken out of chests, barrels, shulker boxes, hoppers, furnaces and the like (`a:container`,
  `a:deposit`, `a:-container`), also for named and enchanted items. The inspector shows the item history when you right click a container
  (double chests share one history). New permission `justlogs.lookup.container`, new `logging.containers` switch.
- Container rollback: `rollback`, `restore` and `undo` also work on `a:container` queries and move the items back.
- A broken or exploded chest keeps its contents; a block rollback puts them back into the restored chest.
- Schema v3 (new table `jl_rollback_crows`), config-version 4. Existing installs are migrated and the config is updated automatically.
- README and the Modrinth/Spigot descriptions are filled in.
