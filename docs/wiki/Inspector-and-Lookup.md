# Inspector and Lookup

## Inspector

`/jo inspect` (or `/jo i`) toggles inspector mode. While it's on, clicks don't break, place or use anything.

| Click | Shows |
|---|---|
| **Left click** a block | The full history of that block, newest first |
| **Right click** a block | The history of the spot next to it (where a block would be placed), useful for finding out who removed a block that is no longer there |
| **Right click** a container | Who put what in and took what out. Needs `justlogs.lookup.container`. Both halves of a double chest share one history. |

Run `/jo inspect` again to turn it off. Inspector mode also ends when you leave the server.

## Lookup

`/jo lookup <parameters>` (or `/jo l`) searches the log. See [Query Syntax](Query-Syntax) for all parameters.

```
/jo lookup u:Notch t:1d
```

`/jo near [radius]` is a shortcut for "everything around me in the last 3 days".

### Reading the results

Each row shows:

```
5m ago · Notch · broke diamond_ore · (120, 12, -40)
```

- **Time** since the change.
- **Who**: a player, or a source like `#tnt`. Click the name to start a new lookup for that player.
- **What**: the action and the block or item.
- **Where**: click the coordinates to teleport there (needs `justlogs.teleport`).

With `#verbose` you also get the record id, the exact time and the block state before and after (for containers: the container type and item data).

With `#count` you only get the number of matching records.

### Paging

Results come in pages of `lookup.page-size` (8) rows. Click ◀ / ▶ under the result, or use `/jo page <n>`. Your last result is kept for `lookup.result-ttl-seconds` (5 minutes).

Pages are reached one by one (each page remembers where the next one starts), so jump forward with the arrows rather than straight to page 50.

On very large results the page counter stops counting at `lookup.count-cap` and shows e.g. `1/100000+`.

### Up-to-date results

Before each lookup, JustLogs waits until the writer has saved everything still queued, so a change made a second ago is already in the result.

## From the console

The console has no position. Give it a scope that doesn't need one (`u:`, `b:`, `w:`, `r:#world`, `r:#global`) or a position with `at:x,y,z` and `w:world`:

```
jo lookup at:120,64,-40 w:world r:5 t:1d
jo lookup u:Griefer t:7d
```
