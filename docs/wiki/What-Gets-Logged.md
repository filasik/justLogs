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
| Crops, cactus, sugar cane, bamboo, kelp grow | `grow` | `#nature` | `crop-growth` (off by default) |
| Fluid flows over another block | `flow` | The player who poured the bucket, else `#water` / `#lava` | `fluids` |
| Sand, gravel, concrete powder, anvil falls | `fall` | `#nature` | `falling-blocks` |
| Piston extends or retracts blocks | `piston` | `#piston` | `pistons` (off by default) |
| Ice, obsidian, concrete, portals, sponge, spread, melt | `form` / `fade` / `spread` | `#nature` / `#sponge` | `form-fade` |
| Leaves decay | `decay` | `#nature` | `leaf-decay` (off by default) |
| Mob changes a block (enderman, silverfish, farmland trampling, ...) | `entity-change` | The player if it was a player, else the entity type, e.g. `#enderman` | `entity-changes` |

Each record stores the time, who, the action, the world and position, and the block **before and after** (material and block state, e.g. `oak_stairs[facing=north,half=bottom]`).

## Signs, Chat, Commands, Sessions & Interactions

- **Signs** (`logging.signs: true`): every edit of a sign's front or back side is recorded with old and new text (`a:sign`, `#verbose` shows old text).
- **Chat** (`logging.chat: true`): player chat messages (`a:chat`, searchable with `k:`).
- **Commands** (`logging.commands: true`): executed commands (`a:command`, searchable with `k:`). Sensitive auth commands (`/login`, `/register`, `/changepassword`, `/2fa`, `/totp`, `/email`, `/auth`) have their arguments redacted to `***` before entering the queue; `logging.command-blacklist` skips listed commands completely.
- **Sessions** (`logging.sessions: true`): player joins and quits (`a:session`, `+session`, `-session`) with HMAC-SHA256 hashed IPs (`secrets.key`). Raw IP storage is off by default (`logging.log-raw-ip: false`).
- **Interactions** (`logging.interactions: true`): clicks on doors, trapdoors, fence gates, buttons, levers, pressure plates, note blocks and bells (`a:click`), debounced per player and block position.

## Items & Entities

- **Items**: dropped (`item-drops: true`, `a:drop`), picked up (`item-pickups: false`, `a:pickup`), crafted (`item-crafts: false`, `a:craft`) with deduplicated item metadata.
- **Entities**: entity kills (`entity-kills: true`, `a:kill`), player deaths (`player-deaths: true`, `a:player-death`), item frames & paintings (`hanging: true`, `a:hanging-place`, `a:hanging-break`), vehicles (`vehicles: true`, `a:vehicle-break`), plus shear, tame, leash and bucket captures (`a:entity`). Filter by entity type with `e:`.

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

- item holders (lecterns, jukeboxes, decorated pots, chiseled bookshelves, armor stand slots)
- entity rollback and sign text rollback
- WorldEdit changes (`a:worldedit`, `r:#worldedit`)
- Web UI (planned)

## Performance

Listeners only put records on an in-memory queue; one background thread writes them in batches (`queue.batch-size`, every `queue.flush-interval-ms`). If the database falls far behind, the queue drops the least important records first (see `queue.max-size`). `/jo status` shows the queue size, write rate, last flush time and anything dropped.
