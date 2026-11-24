# Changelog

## 0.4.1 - 2026-11-24
- Rollback previews send batched block updates for chunks sent to the player. Cancelling or expiring a preview reads the real blocks on their region threads and restores the view on the player’s thread.
- Verified block and container rollback, restore and undo across independent regions on Folia 26.3.

## 0.4.0 - 2026-11-22
- Chat, command and session logging (`a:chat`, `a:command`, `a:session`, `+session`, `-session`):
  - Authentication commands (`/login`, `/register`, `/changepassword`, `/2fa`, `/totp`, `/email`, `/auth`) have their arguments redacted to `***` before entering the queue; `logging.command-blacklist` skips listed commands completely.
  - Player join/quit sessions store HMAC-SHA256 hashed IPs (`secrets.key`), with optional raw IP logging (`logging.log-raw-ip`, off by default).
- Interaction logging (`a:click`): doors, buttons, levers, pressure plates, note blocks and bells, debounced per player and block position.
- Item logging (`a:item`, `a:drop`, `a:pickup`, `a:craft`): dropped, picked up and crafted items with deduplicated item metadata.
- Entity logging (`a:entity`, `a:kill`): entity kills, player deaths, item frame/painting place and break, vehicle destruction, shearing, taming, leashing and bucket captures. Filter by entity type with `e:<entity>` (supports `!` negation).
- Keyword text search `k:<word>` (supports quoted phrases like `k:"diamond sword"`) across chat, commands and signs, plus multi-category keyset-paged queries (e.g. `a:chat,command`).
- Per-category lookup permissions (`justlogs.lookup.chat`, `justlogs.lookup.command`, `justlogs.lookup.session`, `justlogs.lookup.entity`, `justlogs.lookup.item`, `justlogs.lookup.interact`).
- Log purging and automatic retention: `/jo purge t:<age> [a:<category>] [w:<world>] [#optimize]` with `/jo purge confirm`, orphaned attachment cleanup (`jl_block_nbt`, `jl_item_data`), and optional `purge.retention-days`.
- Config-version 7 (existing configs are updated automatically).

## 0.3.0 - 2026-11-22
- Sign logging: every text edit of a sign (front or back side) is recorded with the old and new text. Look it up with `a:sign` (for example `/jo lookup a:sign r:20 t:1d`) or `#verbose` for the old text. Signs are not part of rollback yet.
- New `logging.signs` switch (config-version 6, existing configs are updated automatically).

## 0.2.2 - 2026-11-22
- Extended natural-cause logging: fluid flow (`FLOW`, attributed to the player who poured the bucket when known), falling blocks (`FALL`), piston extend/retract (`PISTON`, off by default), block form/fade/spread/sponge/portals (`FORM`, `FADE`, `SPREAD`), crop growth (`crop-growth`, off by default), bone meal fertilization, and attached blocks popped by breaking a support block (`BlockDestroyEvent`).
- Added disk overflow `Spool` (`plugins/JustLogs/spool/`): unsaved log batches are written safely to disk during database outages or shutdown timeouts and replayed in order once the database is back; `/jo status` shows spooled record counts when present.
- Config-version 5: added `logging.fluids`, `logging.falling-blocks`, `logging.pistons`, `logging.form-fade`, and `logging.crop-growth` (existing configs are updated automatically).

## 0.2.1 - 2026-11-17
- Fixed the [download] button in the "new version available" message (the link was shown as raw text).

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
