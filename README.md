# JustLogs

Block and container logging with an inspector, lookup and rollback. Know who broke, placed, burned, blew up or took what, and put it back.

## Installation
1. Download the `.jar` from Modrinth / GitHub Releases.
2. Put it into `plugins/` on a Paper server.
3. Start the server (the first start needs internet access: database drivers are downloaded once from Maven Central).

## Commands and permissions
Root command `/justlogs`, short aliases `/jo` and `/jlogs`. Everything is `op` by default.

| Command | Permission | Description |
|---|---|---|
| `/justlogs inspect` | `justlogs.inspect` | Inspector mode: left click a block for its history, right click the spot next to it; right click a container for its item history |
| `/justlogs lookup <parameters>` | `justlogs.lookup` | Search the log (aliases `/jo`, `/jlogs`; `/jo l`) |
| `/justlogs near [radius]` | `justlogs.lookup` | Changes around you in the last 3 days |
| `/justlogs page <n>` | `justlogs.lookup` | Another page of the last result |
| `/justlogs rollback <parameters>` | `justlogs.rollback` | Put blocks (or container items with `a:container`) back; needs `t:` and `u:` or `r:`; `#preview` shows it to you first |
| `/justlogs restore <parameters>` | `justlogs.restore` | Apply rolled-back changes again |
| `/justlogs undo` | `justlogs.rollback` | Reverse your last rollback or restore |
| `/justlogs confirm / cancel` | `justlogs.rollback or .restore` | Run or drop a rollback that is waiting for confirmation |
| `/justlogs tp <world> <x> <y> <z>` | `justlogs.teleport` | Target of the clickable coordinates in results |
| `/justlogs status` | `justlogs.status` | Queue, writer, database and rollback state |
| `/justlogs reload` | `justlogs.reload` | Reload config.yml and messages |

Extra permissions: `justlogs.lookup.container` (look up container contents), `justlogs.lookup.global` (search the whole server, radius above the limit), `justlogs.rollback.global` (roll back without a radius), `justlogs.helper` (package: look things up, never change anything), `justlogs.admin` (everything).

## Query parameters
`u:Notch` player (`u:!Notch` everybody else, `u:#tnt` a non-player source), `t:2d` time back from now (`t:2d-1d` a range),
`r:10` radius (`r:#world`, `r:#chunk`, `r:#global`), `a:-block` action or group (`block`, `+block`, `-block`, `container`,
`+container`, `-container`, or one like `break`, `explode`, `deposit`), `b:diamond_ore` block or item (`b:#ores` tag),
`w:world`, `at:x,y,z`, flags `#count`, `#verbose`, `#preview`.

Example: `/jo rollback u:Griefer t:2h r:30 #preview`

## Known limits
- Rolling back a container only restores its item contents; other tile entities (signs, banners, heads, spawners) are not restored yet.
- Not logged yet: fluid flow, falling blocks, pistons, signs, entities, chat, commands, sessions, WorldEdit.
- Folia support is built in (region schedulers) but has not been tested on a Folia server.

## Configuration
See `plugins/JustLogs/config.yml` (every option is commented). When you update the plugin, new options are added to your
existing file automatically; your values stay and a backup `config.yml.bak-v<n>` is saved first.
Messages live in `plugins/JustLogs/messages_<locale>.yml` (MiniMessage); set `locale` in config.yml.

## Build
```
./gradlew build        # compiles (warnings are errors), runs tests, jar in build/libs
./gradlew runServer    # local test Paper server (-PrunDir=<path> reuses an existing server directory)
./scripts/smoke-test.sh
```

## Support the project
The plugin is free. If you like it, you can contribute voluntarily (no in-game perks): [donate via PayPal](https://www.paypal.com/donate/?hosted_button_id=94D2D46EHKU5S).

## License
MIT
