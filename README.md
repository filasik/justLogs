# JustLogs

Fast, modern block, container and player logging with rollback and a web dashboard.

## Installation
1. Download the `.jar` from Modrinth / GitHub Releases.
2. Put it into `plugins/` on a Paper server.
3. Start the server (the first start needs internet access: database drivers are downloaded once from Maven Central).

## Commands and permissions
| Command | Permission | Description |
|---|---|---|
| `/justlogs` | `justlogs.use` (everyone) | Plugin info |
| `/justlogs reload` | `justlogs.admin` (OP) | Reload config.yml and messages |

_TODO: the plugin's own commands_

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
