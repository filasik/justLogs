# What Gets Logged

Each logger can be switched off in the `logging:` section of `config.yml` (see [Configuration](Configuration#logging-what-gets-recorded)). Worlds in `logging.worlds-blacklist` are never logged.

## Blocks

| Event | Action | Who | Switch |
|---|---|---|---|
| Player places a block | `place` | The player. Beds, doors and tall plants log every block they occupy. | `block-place` |
| Player breaks a block | `break` | The player. A container's contents are saved too. | `block-break` |
| Water, lava or powder snow bucket emptied | `bucket-empty` | The player | `buckets` |
| Bucket filled | `bucket-fill` | The player | `buckets` |
| Fire starts | `ignite` | See [attribution](#who-gets-the-blame) | `fire` |
| Block burns away | `burn` | The player who lit the fire, else `#fire` | `fire` |
| Explosion destroys blocks | `explode` | See [attribution](#who-gets-the-blame). Container contents are saved. | `explosions` |
| Tree or huge mushroom grows | `grow` | The player who used bone meal, else `#nature` | `growth` |
| Leaves decay | `decay` | `#nature` | `leaf-decay` (off by default) |
| Mob changes a block (enderman, silverfish, farmland trampling, ...) | `entity-change` | The player if it was a player, else the entity type, e.g. `#enderman` | `entity-changes` |

Each record stores the time, who, the action, the world and position, and the block **before and after** (material and block state, e.g. `oak_stairs[facing=north,half=bottom]`).

## Containers

With `logging.containers: true`, items put into and taken out of chests, barrels, shulker boxes, hoppers, furnaces, dispensers and other containers are logged as `deposit` and `withdraw` (`a:container`).

How it works: when a player opens a container, JustLogs takes a snapshot of it and compares it after every click and on close. The net change per item type becomes one record, so shift-clicks, drags and number keys are all handled the same way. Named and enchanted items keep their item data.

A double chest is always stored under its left half, so both halves share one history.

Player inventories, ender chests, crafting tables and entity inventories (horses, minecarts with chests) are not containers in this sense and aren't logged.

## Who gets the blame

JustLogs tries to name the player behind indirect damage:

- **Fire**: the player who lit it (flint and steel, fire charge). Fire that spreads from that fire stays theirs for about 2 minutes. Otherwise `#fire`, `#lava` or `#lightning`.
- **TNT**: the player who lit it. TNT set off by other TNT inherits the owner, and TNT lit by an owned fire belongs to the fire's owner. Otherwise `#tnt`.
- **Other explosions**: the exploding entity type (`#creeper`, `#wither`, `#end_crystal`, ...). Block explosions such as beds in the Nether are `#explosion`.

## Not logged yet

The query language already knows these actions, but nothing records them yet, so they return no results:

- fluid flow, falling blocks (sand, gravel), pistons, blocks formed or faded (snow, ice, concrete), dispensers
- signs (text), items dropped/picked up/crafted, item frames and armor stands
- entities (kills, deaths, leashing, ...), clicks on doors and buttons
- chat, commands, logins and logouts
- WorldEdit changes

## Performance

Listeners only put records on an in-memory queue; one background thread writes them in batches (`queue.batch-size`, every `queue.flush-interval-ms`). If the database falls far behind, the queue drops the least important records first (see `queue.max-size`). `/jo status` shows the queue size, write rate, last flush time and anything dropped.
