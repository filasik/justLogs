# Messages and Translations

Every text players see lives in `plugins/JustLogs/messages_<locale>.yml`. The language is chosen with `locale` in `config.yml`.

Bundled languages:

| Locale | Language |
|---|---|
| `en` | English (default) |
| `cs` | Czech |

Both files are copied to the plugin folder on the first start. They are yours to edit; JustLogs never overwrites them.

## Format

Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html): colors (`<red>`, `<gold>`), click and hover events, and so on.

```yaml
prefix: '<dark_gray>[<gold>JustLogs<dark_gray>]<reset>'

inspect:
  enabled: '<prefix> <green>Inspector on. <gray>Left click a block for its history, right click for the spot next to it.'
```

- `<prefix>` inserts the `prefix` message.
- Placeholders in angle brackets (`<version>`, `<query>`, `<x>`, `<count>`, ...) are filled in by the plugin. **Keep them** when you change a text.
- Clickable parts (`lookup.pos`, `lookup.user`, `lookup.prev`/`next`, the `[/jo confirm]` buttons) run commands. Keep the `<click:...>` tags if you want them to stay clickable.

After editing, run `/jo reload`.

## Missing keys

For each key, JustLogs looks in your file first, then in the bundled file of the same language, then in the built-in English text. So:

- after an update that adds new messages, your old file keeps working, and the new keys appear in English (or the bundled translation) until you add them;
- you can delete keys you didn't change, if you prefer a short file.

## Adding a language

1. Copy `messages_en.yml` to `messages_<code>.yml`, e.g. `messages_de.yml`.
2. Translate the texts, keeping the placeholders.
3. Set `locale: de` in `config.yml` and run `/jo reload`.

If the file doesn't exist, JustLogs warns in the console and uses English.

Want to share it? Open a pull request that adds the file to `src/main/resources/`. See [Development](Development).
