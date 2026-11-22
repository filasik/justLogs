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
| | `a:sign` | Sign text edits |
| | `a:chat` | Player chat messages |
| | `a:command` | Executed commands |
| | `a:session` | Player logins and logouts (`+session` = login, `-session` = logout) |
| | `a:click` | Block interactions (doors, buttons, levers, pressure plates) |
| | `a:item` | Dropped, picked up and crafted items (`drop`, `pickup`, `craft`) |
| | `a:entity` | Entity kills, deaths, hanging, vehicles, tame, shear, leash |
| | `a:break,explode` | Single actions (see the [list](#actions)) |
| `b:` block or item | `b:diamond_ore` | A block or item type. The `minecraft:` prefix is optional. |
| | `b:!stone,dirt` | Everything **except** these |
| | `b:#logs` | A Minecraft block or item tag (block tags are tried first) |
| `e:` entity | `e:creeper` | Entity type (`e:!villager` excludes) |
| `k:` keyword | `k:diamond` | Text search in chat, commands and signs (`k:"hello world"` for phrases) |
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
- **No scope at all** (no `u:`, `r:`, `b:`, `e:`, `k:`, `w:` or `at:`) → a player gets `r:<lookup.default-radius>` (10) around them. The console gets an error and must say what to search.
- **`r:` larger than `lookup.max-radius`** (100) or `r:#global` → needs `justlogs.lookup.global`.
- **Category permissions** → `a:container` needs `justlogs.lookup.container`; `chat`, `command`, `session`, `entity`, `item` and `click` each need `justlogs.lookup.<category>`.

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
| `grow` | `+block` | Tree, mushroom or crop grew |
| `flow` | `+block` | Water or lava flowed over a block |
| `fall` | `block` | Sand, gravel, concrete powder or anvil fell |
| `piston` | `block` | Piston extended or retracted (only with `logging.pistons`) |
| `form` / `fade` / `spread` | `block` | Block formed, melted/faded or spread |
| `decay` | `-block` | Leaves decayed (only with `logging.leaf-decay`) |
| `entity-change` | `block` | A mob (or a player trampling farmland, etc.) changed a block |
| `deposit` / `withdraw` | `container` | Items put into or taken out of a container |
| `sign-edit` | `sign` | Sign text edited (front or back) |
| `chat` | `chat` | Player chat message |
| `command` | `command` | Player command (auth commands redacted) |
| `login` / `logout` | `session` | Player joined or left |
| `click` | `interact` | Door, button, lever or pressure plate used |
| `drop` / `pickup` / `craft` | `item` | Item dropped, picked up or crafted |
| `kill` / `player-death` | `entity` | Entity killed or player died |
| `hanging-place` / `hanging-break` | `entity` | Item frame or painting placed/broken |
| `vehicle-break` | `entity` | Boat or minecart destroyed |
| `tame` / `shear` / `leash` / `bucket-entity` | `entity` | Entity tamed, sheared, leashed or bucketed |

A query can combine categories (for example `a:chat,command` or `k:diamond` across chat, commands and signs) and pages across them with keyset pagination. Only `a:worldedit` is reserved for a future release.

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
