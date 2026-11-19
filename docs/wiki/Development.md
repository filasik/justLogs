# Development

## Requirements

- **JDK 25** (Gradle toolchain). The plugin is compiled for Java 21 (`--release 21`).
- Gradle is provided by the wrapper (`./gradlew`).

## Building

```bash
./gradlew build          # compile (warnings are errors), run the tests, jar in build/libs/
./gradlew runServer      # start a local Paper test server with the plugin
./gradlew runServer -PrunDir=/path/to/server   # reuse an existing test server directory
./scripts/smoke-test.sh  # build, start a clean Paper server, check the plugin enabled without errors, stop it
```

The release artifact is the shadow jar (`build/libs/JustLogs-<version>.jar`). Only bStats is shaded (relocated to `me.skerik.justlogs.libs.bstats`). SQLite, MariaDB and HikariCP are listed in `src/main/resources/libraries.txt` and downloaded by `JustLogsLoader` (a Paper `PluginLoader`) on the first start.

Code compiles with `-Xlint:all -Werror`: no deprecated API without an explicit, commented suppression.

## Versions

All versions live in `gradle.properties`:

| Property | Meaning |
|---|---|
| `pluginVersion` | Plugin version |
| `paperApiVersion` | Oldest supported Paper API (compiled against it) |
| `apiVersion` | `api-version` in `paper-plugin.yml` |
| `minecraftVersion` | Version for `runServer` and Modrinth |
| `bstatsId` | bStats plugin id (`0` = no metrics in this build) |
| `sqliteVersion`, `mariadbVersion`, `hikariVersion` | Runtime libraries |

## Tests

```bash
./gradlew test
```

Unit tests cover the query parser, the SQL query compiler, migrations, the batch writer, container storage, rollback planning, config updates and message files.

There is also an optional MariaDB/MySQL integration test. It runs only when `JL_TEST_MARIADB` is set, and it **drops every `jl_` table** in that database, so point it at a throwaway one:

```bash
JL_TEST_MARIADB=127.0.0.1:3306/justlogs_test \
JL_TEST_MARIADB_USER=root JL_TEST_MARIADB_PASSWORD=secret \
./gradlew test
```

## Project layout

Package `me.skerik.justlogs`:

| Package | Contents |
|---|---|
| (root) | `JustLogs` (startup order), `JustLogsLoader` (library download), `Context`, `Scheduler` / `PaperScheduler` (Folia-safe scheduling) |
| `command` | `/justlogs` (Brigadier), lookup service, result paging, rendering, tab completion |
| `config` | `Settings`, `Messages`, `ConfigUpdater` |
| `inspect` | Inspector mode |
| `listener` | Block, nature (fire, explosions, growth, mobs) and container listeners, `CauseTracker` (who lit what) |
| `model` | `Action`, `Category`, `Actor`, records |
| `query` | `QueryParser`, `LogQuery`, `ActionGroups`, `Radius` |
| `queue` | In-memory `LogQueue` and the `BatchWriter` thread |
| `rollback` | Planning (`RollbackPlan`, `ContainerPlan`), applying chunk by chunk, `RollbackService` |
| `storage` | `Database` (HikariCP), `Migrations`, `QueryCompiler` (query to SQL), id cache, stores |
| `util` | Durations, keys, HMAC, secrets, update checker, bStats, banner |

### Startup

1. `onEnable` (main thread, fast): config and its automatic update, settings, messages, scheduler, command registration.
2. Async: open the database, run migrations, load the id cache, start the writer.
3. First tick: build the `Context`, register listeners (nothing is logged before the writer exists), start timers, print the banner.

A fatal problem (database unreachable) logs one clear line and disables the plugin.

## Rules for changes

These come from the release checklist (`docs/RELEASE_CHECKLIST.md`):

- **New config option**: add it to `config.yml` with a comment, raise `config-version`, extend `ConfigUpdaterTest`.
- **Schema change**: append a new version to `Migrations.VERSIONS`. Never edit or reorder an old one, and add an upgrade test.
- **New action**: append to `Action` with a new id. Never change or reuse an id; they are stored in the database.
- **New message key**: add it to `messages_en.yml` and every bundled locale (`MessagesFilesTest` checks this).
- **Threads**: no blocking I/O on the main thread; schedule everything through `Scheduler` so Folia keeps working.
- Update `CHANGELOG.md`.

## Releasing

1. Go through `docs/RELEASE_CHECKLIST.md`.
2. Set `pluginVersion` in `gradle.properties` and add a `## <version>` section to `CHANGELOG.md` (the build fails without it).
3. Push a tag `vX.Y.Z`. The `Release` workflow runs the smoke test, creates a GitHub Release with the jar and the changelog section, and publishes to Modrinth (`MODRINTH_TOKEN` secret).
4. SpigotMC is uploaded by hand (description in `docs/spigot.bbcode.txt`).

Every push to `main` and every pull request runs the `Build` workflow (build, tests, smoke test) and uploads the jar as an artifact.

## Contributing

- Open an issue before larger changes.
- Keep pull requests focused and update `CHANGELOG.md`.
- Contributions are licensed under MIT.
- Security issues: report privately through GitHub's "Report a vulnerability", not as a public issue.
