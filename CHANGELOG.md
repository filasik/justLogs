# Changelog

## Unreleased
- Logs blocks placed and broken by players and water/lava buckets (queued in memory, written in batches by one thread).
- `/justlogs` (`/jo`, `/jlogs`): `inspect`, `lookup`, `near`, `page`, `tp`, `status`, `reload`, `help` with a free-form
  query (`u: t: r: a: b: w: at:` and `#count`, `#verbose`), tab completion, clickable positions and paging.
- SQLite (default) and MariaDB/MySQL storage with a versioned schema; English and Czech messages.
