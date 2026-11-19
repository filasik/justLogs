# FAQ

### The plugin doesn't start on the first run

The first start downloads the database drivers from Maven Central. Check that the server can reach the internet. If it can't, start it once on a machine that can and copy the server's `libraries/` folder over. See [Installation](Installation#why-the-first-start-needs-internet).

### "Cannot open the database ... JustLogs is disabling itself"

The storage settings in `config.yml` are wrong or the database is unreachable. Check `storage.type` and the `mariadb` host, port, database, user and password, then restart.

### MySQL 8: "Cannot use authentication plugin caching_sha2_password with a Self signed certificates"

MySQL 8+ uses a self-signed certificate by default. Set:

```yaml
storage:
  mariadb:
    ssl-mode: "trust"
```

`trust` keeps the connection encrypted without verifying the certificate. Use `disable` only on a trusted local network.

### "JustLogs is still starting up"

The database opens in the background. Commands work as soon as startup has finished, usually within a second.

### A lookup without `t:` misses older changes

Lookups without `t:` cover `lookup.default-time` (3 days). Add `t:30d` or change the default.

### "The largest radius you may use is 100" / "You may not search the whole server"

Your radius is above `lookup.max-radius`, or you used `r:#global`. Use a smaller radius, or get `justlogs.lookup.global`.

### "Rollback and restore need a time and a scope"

A rollback needs `t:` plus `u:` or `r:`. Example: `/jo rollback u:Notch t:1h r:20`.

### "You may not search the whole server" on a rollback

Without `justlogs.rollback.global` a rollback must stay local: `r:<number>` or `r:#chunk`. `u:Name` alone, `r:#world` and `r:#global` need the global permission.

### "Page 5 is not reachable yet"

Pages are loaded one after another. Use the ▶ arrow or `/jo page` with the next number.

### Does a rollback get logged again?

No. The changes a rollback makes are not recorded as new block changes. The rollback itself is recorded so `/jo undo` can reverse it.

### Will a rollback put chest contents back?

Yes. When a container is broken or blown up, its contents are saved, and rolling back the block puts the container and its items back. Item moves into and out of containers are rolled back with `a:container`.

Other block entity data (sign text, banners, heads, spawners) is not restored yet.

### Is WorldEdit logged?

Not yet. `r:#worldedit` is reserved but not supported yet either.

### Can I log several servers into one database?

Yes, with MariaDB/MySQL. Give each server its own `server-name`. See [Storage](Storage#sharing-one-database-between-servers).

### Does it work on Folia?

The code is built for Folia (region schedulers everywhere), but it hasn't been tested on a Folia server yet. Reports are welcome.

### Does it work on Spigot?

No. JustLogs is a Paper plugin and needs Paper 1.21.1 or newer (or a fork such as Purpur).

### The queue is dropping records

`/jo status` shows a dropped count when the database can't keep up. Consider MariaDB on fast storage, a larger `queue.batch-size`, or turning off noisy loggers such as `leaf-decay`.

### How do I turn off statistics or update checks?

`metrics: false` turns off bStats for JustLogs. `update-check: false` turns off the GitHub release check.

### Where do I report a bug?

[GitHub issues](https://github.com/filasik/justLogs/issues). For security problems use GitHub's private "Report a vulnerability" instead.
