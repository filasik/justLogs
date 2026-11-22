# Commands and Permissions

The root command is `/justlogs`, with the short aliases `/jo` and `/jlogs` (configurable in `command.aliases`). Tab completion suggests subcommands and query parameters.

**Everything is `op` by default.** Give non-op staff one of the permission packages below.

## Commands

| Command | Short | Permission | Description |
|---|---|---|---|
| `/jo` | | `justlogs.use` | Version and a pointer to help |
| `/jo help` | | `justlogs.use` | Lists the commands you may use |
| `/jo help params` | | `justlogs.use` | Explains the query parameters |
| `/jo inspect` | `/jo i` | `justlogs.inspect` | Toggle inspector mode. Left click a block for its history, right click for the spot next to it, right click a container for its item history. Players only. |
| `/jo lookup <parameters>` | `/jo l` | `justlogs.lookup` | Search the log. See [Query Syntax](Query-Syntax). |
| `/jo near [radius]` | | `justlogs.lookup` | Changes around you in the last 3 days. Without a number it uses `lookup.default-radius`. |
| `/jo page <n>` | `/jo p` | `justlogs.lookup` | Another page of your last result |
| `/jo rollback <parameters>` | `/jo rb` | `justlogs.rollback` | Put blocks (or container items with `a:container`) back. Needs `t:` and `u:` or `r:`. |
| `/jo restore <parameters>` | `/jo rs` | `justlogs.restore` | Apply rolled-back changes again |
| `/jo undo` | | `justlogs.rollback` | Reverse your last rollback, restore or undo |
| `/jo confirm` | | `justlogs.rollback` or `justlogs.restore` | Run the rollback or restore that is waiting for confirmation |
| `/jo cancel` | | `justlogs.rollback` or `justlogs.restore` | Drop it (and remove the preview's ghost blocks) |
| `/jo tp <world> <x> <y> <z>` | | `justlogs.teleport` | Teleport to a position. This is what the clickable coordinates in results run. Players only. |
| `/jo purge <parameters>` | | `justlogs.purge` | Delete old log rows (`t:<age> [a:<category>] [w:<world>] [#optimize]`). Asks for `/jo purge confirm`. |
| `/jo purge confirm` | | `justlogs.purge` | Run the pending purge |
| `/jo status` | | `justlogs.status` | Database type and schema, queue size, disk spool count, writer rate and errors, rollback state |
| `/jo reload` | | `justlogs.reload` | Reload `config.yml` and messages (not storage, queue sizes or aliases) |

Commands that need the database answer "JustLogs is still starting up" until startup has finished (usually within the first tick).

The console can use lookups and rollbacks too, but it has no position: give it `at:x,y,z` and `w:world`, or a non-radius scope like `u:` or `r:#world`. `#preview` and the inspector are players only.

See [Inspector and Lookup](Inspector-and-Lookup) and [Rollback and Restore](Rollback-and-Restore) for how each works.

## Permissions

| Permission | Default | Description |
|---|---|---|
| `justlogs.use` | op | Base command and help |
| `justlogs.inspect` | op | Inspector mode |
| `justlogs.lookup` | op | `/jo lookup`, `/jo near`, `/jo page` |
| `justlogs.lookup.container` | op | Look up what was put into and taken out of containers (also needed to roll back container items, and for the inspector's container history) |
| `justlogs.lookup.chat` | op | Look up chat messages (`a:chat`) |
| `justlogs.lookup.command` | op | Look up executed commands (`a:command`) |
| `justlogs.lookup.session` | op | Look up player login/logout sessions (`a:session`) |
| `justlogs.lookup.entity` | op | Look up entity kills, deaths, hanging and vehicle events (`a:entity`) |
| `justlogs.lookup.item` | op | Look up dropped, picked up and crafted items (`a:item`) |
| `justlogs.lookup.interact` | op | Look up block interactions (`a:click`) |
| `justlogs.lookup.global` | op | Search the whole server (`r:#global`) and use a radius above `lookup.max-radius` |
| `justlogs.rollback` | op | `/jo rollback`, `/jo undo`, `/jo confirm` |
| `justlogs.restore` | op | `/jo restore` |
| `justlogs.rollback.global` | op | Roll back or restore without a radius: by player only, `r:#world` or `r:#global`, and radii above `lookup.max-radius` |
| `justlogs.purge` | op | `/jo purge` and `/jo purge confirm` |
| `justlogs.teleport` | op | Click a coordinate in a result to teleport there |
| `justlogs.status` | op | `/jo status` |
| `justlogs.reload` | op | `/jo reload` |
| `justlogs.notify` | op | Told about new plugin versions on join |

### Packages

| Permission | Default | Includes |
|---|---|---|
| `justlogs.helper` | false | Look things up, never change anything: `use`, `inspect`, `lookup`, `lookup.container`, `lookup.chat`, `lookup.command`, `lookup.session`, `lookup.entity`, `lookup.item`, `lookup.interact`, `teleport` |
| `justlogs.admin` | op | Everything: `helper` plus `lookup.global`, `rollback`, `restore`, `rollback.global`, `purge`, `status`, `reload`, `notify` |

### Example with LuckPerms

```
/lp group helper permission set justlogs.helper true
/lp group moderator permission set justlogs.helper true
/lp group moderator permission set justlogs.rollback true
/lp group admin permission set justlogs.admin true
```

A moderator set up like this can roll back within `lookup.max-radius` around themselves (`r:30`, `r:#chunk`), but not server-wide.
