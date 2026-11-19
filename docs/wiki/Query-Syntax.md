# Query Syntax

`/jo lookup`, `/jo rollback` and `/jo restore` all take the same parameters. They can come in any order, separated by spaces. Several values for one parameter are separated by commas (no spaces).

```
/jo lookup u:Notch,jeb_ t:2d r:20 a:-block b:diamond_ore,emerald_ore
```

In game, `/jo help params` shows a short version of this page.

## Parameters

| Parameter | Example | Meaning |
|---|---|---|
| `u:` user | `u:Notch` | Player name. Several: `u:Notch,jeb_`. |
| | `u:!Notch` | Everybody **except** these |
| | `u:#tnt` | A non-player source (see [sources](#sources)) |
| `t:` time | `t:2d` | From 2 days ago until now |
| | `t:2d-1d` | A range: between 2 days and 1 day ago (order doesn't matter) |
| | `t:1h30m` | Units combine |
| `r:` radius | `r:10` | A cube of ±10 blocks around you (or around `at:`) |
| | `r:#chunk` | The chunk you stand in |
| | `r:#world` | The whole world (yours, or `w:`) |
| | `r:#global` | The whole server. Needs `justlogs.lookup.global`. |
| `a:` action | `a:block` | All block actions |
| | `a:+block` | Blocks added (place, ignite, grow, pour, ...) |
| | `a:-block` | Blocks removed (break, burn, explode, decay, scoop up, ...) |
| | `a:container` | Container item moves (`+container` = put in, `-container` = taken out) |
| | `a:break,explode` | Single actions (see the [list](#actions)) |
| `b:` block or item | `b:diamond_ore` | A block or item type. The `minecraft:` prefix is optional. |
| | `b:!stone,dirt` | Everything **except** these |
| | `b:#logs` | A Minecraft block or item tag (block tags are tried first) |
| `w:` world | `w:world_nether` | Only this world |
| `at:` position | `at:100,64,-200` | Centre of `r:` instead of your own position (useful from the console) |

### Time units

`s` seconds, `m` minutes, `h` hours, `d` days, `w` weeks. A bare number means seconds. Maximum is 10 years.

### Flags

| Flag | Meaning |
|---|---|
| `#count` | Only show the number of matching records |
| `#verbose` | More detail per row: record id, exact time, and the block state before and after (for containers: the holder and the item data) |
| `#preview` | Rollback and restore only: show the result to you as ghost blocks first. See [Rollback and Restore](Rollback-and-Restore#preview). |

## Defaults and limits

### Lookups

- **No `t:`** → `lookup.default-time` (3 days).
- **No scope at all** (no `u:`, `r:`, `b:`, `w:` or `at:`) → a player gets `r:<lookup.default-radius>` (10) around them. The console gets an error and must say what to search.
- **`r:` larger than `lookup.max-radius`** (100) or `r:#global` → needs `justlogs.lookup.global`.
- **`a:container`** and the other container actions → need `justlogs.lookup.container`.

### Rollback and restore

- Must have **`t:`** (no default time) and **`u:` or `r:`**.
- Without `justlogs.rollback.global` you must use `r:<number>` (up to `lookup.max-radius`) or `r:#chunk`.

## Actions

Actions that JustLogs logs today:

| Action | Group | Meaning |
|---|---|---|
| `break` | `-block` | Player broke a block |
| `place` | `+block` | Player placed a block |
| `bucket-empty` | `+block` | Water or lava poured |
| `bucket-fill` | `-block` | Water or lava scooped up |
| `ignite` | `+block` | Fire started |
| `burn` | `-block` | Block burned away |
| `explode` | `-block` | Block destroyed by an explosion |
| `grow` | `+block` | Tree or huge mushroom grew |
| `decay` | `-block` | Leaves decayed (only with `logging.leaf-decay`) |
| `entity-change` | `block` | A mob (or a player trampling farmland, etc.) changed a block |
| `deposit` | `+container` | Items put into a container |
| `withdraw` | `-container` | Items taken out of a container |

The query language already knows actions and groups for things that aren't logged yet (`flow`, `fall`, `piston`, `sign`, `item`, `kill`, `entity`, `chat`, `command`, `session`, `click`, `worldedit`, ...). They are accepted but return no results until those loggers ship. See [What Gets Logged](What-Gets-Logged#not-logged-yet).

A query searches **either** block history **or** container history. If every action in `a:` is a container action, it searches containers; otherwise (including no `a:` at all) it searches blocks.

## Sources

When no player can be named, the user is a source that starts with `#`:

| Source | When |
|---|---|
| `#fire` | Fire nobody can be blamed for |
| `#lava` | Fire started by lava |
| `#lightning` | Fire started by lightning |
| `#tnt` | TNT that no player lit |
| `#creeper`, `#wither`, `#ghast`, ... | The entity type that exploded or changed a block (`#enderman`, `#silverfish`, ...) |
| `#explosion` | Block explosions (beds, respawn anchors) |
| `#nature` | Natural growth and leaf decay |

## Examples

```
/jo lookup u:Griefer t:1d                        everything Griefer did today
/jo lookup r:20 a:-block t:6h                    blocks removed within 20 blocks in the last 6 hours
/jo lookup b:diamond_ore a:break t:7d r:#world   who mined diamonds this week in this world
/jo lookup u:#creeper r:50 #count                how many blocks creepers destroyed nearby
/jo lookup a:-container b:#shulker_boxes r:10    who took shulker boxes out of chests nearby
/jo lookup at:120,64,-40 w:world r:5 t:3d        from the console
/jo rollback u:Griefer t:2h r:30 #preview        preview the rollback first
/jo rollback u:#tnt t:30m r:40                   undo TNT damage nearby
/jo rollback a:container u:Thief t:1d r:20       put stolen items back
```
