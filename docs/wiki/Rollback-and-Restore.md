# Rollback and Restore

A **rollback** puts the world back the way it was before the matching changes. A **restore** applies rolled-back changes again. **Undo** reverses your last rollback or restore.

```
/jo rollback u:Griefer t:2h r:30 #preview
/jo confirm
```

## Rules

- A rollback must have a time **`t:`** and a scope **`u:` or `r:`**. There is no default time, so a rollback never silently covers "the last 3 days".
- Without `justlogs.rollback.global` you must use a local scope: `r:<number>` up to `lookup.max-radius`, or `r:#chunk`. Rolling back a player everywhere (`u:` without `r:`), `r:#world` and `r:#global` need `justlogs.rollback.global`.
- Rolling back container items (`a:container`) also needs `justlogs.lookup.container`.
- Only **one rollback runs at a time** on the server. Others get "Another rollback is running".
- The changes a rollback makes are **not logged again**, so they don't pollute the history.

All [query parameters](Query-Syntax) work, so you can be precise:

```
/jo rollback u:Griefer t:1d r:50 a:-block          only what Griefer broke
/jo rollback u:#creeper,#tnt t:1h r:40              explosion damage only
/jo rollback r:20 t:30m b:lava a:bucket-empty       lava someone poured
/jo rollback u:Thief t:1d r:20 a:container          put stolen items back
```

## How a rollback decides what to set

For each block position, the **oldest** matching record wins: the block goes back to the state it had before that record. For a restore, the **newest** matching record wins: the block goes to the state after it.

Only records that haven't been rolled back yet are used for a rollback, and only rolled-back records for a restore. So running the same rollback twice does nothing the second time.

When a chest, barrel or other container was broken or blown up, its contents were saved with the record. Rolling the block back puts the chest **and its items** back.

## Preview

Add `#preview` to see the result before anything changes:

1. Only **you** see the changed blocks (ghost blocks sent to your client; nobody else sees them, and the world is untouched).
2. Chat shows how many blocks in how many chunks would change, with clickable **[/jo confirm]** and **[/jo cancel]**.
3. `/jo confirm` applies it. `/jo cancel` (or waiting `rollback.confirm-expire-seconds`, 60 s) drops it and shows you the real blocks again.

Limits:
- Players only (the console can't see ghost blocks).
- Blocks only, not container items.
- At most `rollback.preview-max-blocks` (50,000) blocks. For bigger rollbacks drop `#preview` and use the confirmation step.

## Confirmation

A rollback or restore that changes more than `rollback.confirm-above` (10,000) blocks waits for `/jo confirm`, even without a preview. Set it to `0` to always ask. The confirmation expires after `rollback.confirm-expire-seconds`.

## Applying the changes

Blocks are changed **chunk by chunk** on the thread that owns that chunk (Folia safe), within a budget of `rollback.ms-per-tick` (8 ms) per tick. A large rollback simply takes several ticks instead of freezing the server.

When it's done you get a summary: changed blocks, chunks and time taken. Blocks that couldn't be set (an unknown block type or a world that no longer exists) are counted as **skipped**.

## Container rollback

With `a:container` (or `a:deposit`, `a:withdraw`, `a:+container`, `a:-container`), each item move is undone on its own:

- a rolled-back **withdraw** puts the items back into the container,
- a rolled-back **deposit** takes them out again,
- a restore does the opposite.

Items that are no longer there, or don't fit, are counted as skipped. JustLogs never invents items.

## Undo

`/jo undo` reverses **your** last rollback, restore or undo, using exactly the records that operation changed. Undoing a rollback restores; undoing a restore rolls back.

## Limits

- Rolling back a container block restores its item contents. Other block entity data (signs, banners, heads, spawners) is not restored yet.
- Only what JustLogs logged can be rolled back. See [What Gets Logged](What-Gets-Logged).
- `rollback.max-rows` (500,000) caps how many records one operation may load. Narrow the query if you hit it.
- `r:#worldedit` (a WorldEdit selection) is reserved but not supported yet.
