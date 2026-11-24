package me.skerik.justlogs.rollback;

import io.papermc.paper.math.Position;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.command.LookupService;
import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.config.Settings;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockEntry;
import me.skerik.justlogs.model.Category;
import me.skerik.justlogs.model.ContainerEntry;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import me.skerik.justlogs.query.QueryParser;
import me.skerik.justlogs.query.QuerySyntaxException;
import me.skerik.justlogs.query.Radius;
import me.skerik.justlogs.storage.BlockStore;
import me.skerik.justlogs.storage.ContainerStore;
import me.skerik.justlogs.storage.QueryCompiler;
import me.skerik.justlogs.storage.RollbackStore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Rollback, restore and undo: query, plan, optional preview and confirmation, apply, record. One operation runs at a
 * time. The changes it makes to the world are not logged again (only player and cause listeners write log rows).
 */
public final class RollbackService {

    /** An operation waiting for /jo confirm; {@code previewed} when ghost blocks were sent to the sender. */
    private record Pending(Plan plan, boolean restore, LogQuery query, long expiresAtMillis, boolean previewed) {
    }

    private record UndoData(RollbackStore.Entry entry, Plan plan) {
    }

    private record Done(ApplyResult result, long ms) {
    }

    /** Rows loaded for a new rollback: block rows with their tile entity snapshots, or container rows. */
    private record Loaded(List<BlockEntry> blocks, Map<Long, byte[]> nbt, List<ContainerEntry> containers) {

        int size() {
            return blocks.size() + containers.size();
        }

        Plan plan(boolean restore) {
            return containers.isEmpty() && blocks.isEmpty() ? RollbackPlan.build(List.of(), restore)
                    : !containers.isEmpty() ? ContainerPlan.build(containers, restore) : RollbackPlan.build(blocks, restore, nbt);
        }
    }

    private final BlockStore blockStore = new BlockStore();
    private final ContainerStore containerStore = new ContainerStore();

    private final Context ctx;
    private final RollbackStore store = new RollbackStore();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();

    public RollbackService(Context ctx) {
        this.ctx = ctx;
    }

    // ── entry points (sender's thread) ─────────────────────────────────────────────

    public void start(CommandSender sender, String raw, boolean restore) {
        Messages m = ctx.messages();
        Origin origin = LookupService.origin(sender);
        LogQuery query;
        try {
            query = prepare(sender, raw, origin);
        } catch (QuerySyntaxException e) {
            sender.sendMessage(m.get("query.error." + e.code(), Messages.text("arg", e.arg())));
            return;
        }
        if (query.has(LogQuery.Flag.PREVIEW) && !(sender instanceof Player)) {
            sender.sendMessage(m.get("rollback.preview-players-only"));
            return;
        }
        if (busy.get()) {
            sender.sendMessage(m.get("rollback.busy"));
            return;
        }
        Category cat = QueryCompiler.categoryOf(query);
        if (cat == Category.CONTAINER && query.has(LogQuery.Flag.PREVIEW)) {
            sender.sendMessage(m.get("rollback.preview-blocks-only"));
            return;
        }
        sender.sendMessage(m.get("rollback.planning"));
        int maxRows = ctx.settings().rollback().maxRows();
        ctx.writer.flushAll()
                .thenCompose(v -> ctx.db.supply(c -> {
                    try {
                        if (cat == Category.CONTAINER) {
                            return new Loaded(List.of(), Map.of(), ctx.compiler.forContainerChange(c, query, origin, restore, maxRows));
                        }
                        List<BlockEntry> rows = ctx.compiler.forChange(c, query, origin, restore, maxRows);
                        Map<Long, byte[]> nbt = rows.size() > maxRows ? Map.of() : blockStore.nbtFor(c, rows);
                        return new Loaded(rows, nbt, List.of());
                    } catch (QuerySyntaxException e) {
                        throw new IllegalArgumentException(e); // unwrapped again in failed()
                    }
                }))
                .whenComplete((loaded, error) -> {
                    if (error != null) {
                        failed(sender, error);
                        return;
                    }
                    if (loaded.size() > maxRows) {
                        send(sender, m.get("rollback.too-many", Messages.text("max", String.valueOf(maxRows))));
                        return;
                    }
                    if (loaded.size() == 0) {
                        send(sender, m.get("rollback.nothing", Messages.text("query", query.canonical())));
                        return;
                    }
                    plannedFromQuery(sender, loaded.plan(restore), restore, query);
                });
    }

    /** {@code /jo undo}: reverses the sender's last rollback, restore or undo. */
    public void undo(CommandSender sender) {
        Messages m = ctx.messages();
        if (busy.get()) {
            sender.sendMessage(m.get("rollback.busy"));
            return;
        }
        Actor actor = actor(sender);
        ctx.writer.flushAll()
                .thenCompose(v -> ctx.db.supply(c -> {
                    int userId = ctx.ids.user(c, actor, nowSec());
                    RollbackStore.Entry last = store.lastUndoable(c, userId);
                    if (last == null) return null;
                    // Undoing a restore rolls back, undoing a rollback restores
                    boolean restore = !last.restores();
                    Plan plan;
                    if (last.containers()) {
                        plan = ContainerPlan.build(containerStore.rowsOf(c, last.id()), restore);
                    } else {
                        List<BlockEntry> rows = store.rowsOf(c, last.id());
                        plan = RollbackPlan.build(rows, restore, blockStore.nbtFor(c, rows));
                    }
                    return new UndoData(last, plan);
                }))
                .whenComplete((found, error) -> {
                    if (error != null) {
                        failed(sender, error);
                        return;
                    }
                    if (found == null) {
                        send(sender, m.get("rollback.undo-none"));
                        return;
                    }
                    RollbackStore.Entry last = found.entry();
                    int kind = last.restores() ? RollbackStore.KIND_UNDO_RESTORE : RollbackStore.KIND_UNDO_ROLLBACK;
                    execute(sender, found.plan(), !last.restores(), last.query(), kind, last.id());
                });
    }

    public void confirm(CommandSender sender) {
        Messages m = ctx.messages();
        Pending p = pending.remove(LookupService.key(sender));
        if (p == null) {
            sender.sendMessage(m.get("rollback.none-pending"));
            return;
        }
        if (p.expiresAtMillis() < System.currentTimeMillis()) {
            revertPreview(sender, p);
            sender.sendMessage(m.get("rollback.expired"));
            return;
        }
        execute(sender, p.plan(), p.restore(), p.query().canonical(),
                p.restore() ? RollbackStore.KIND_RESTORE : RollbackStore.KIND_ROLLBACK, null);
    }

    public void cancel(CommandSender sender) {
        Pending p = pending.remove(LookupService.key(sender));
        if (p == null) {
            sender.sendMessage(ctx.messages().get("rollback.none-pending"));
            return;
        }
        revertPreview(sender, p);
        sender.sendMessage(ctx.messages().get("rollback.cancelled"));
    }

    /** Called from a timer: drops pending operations nobody confirmed and removes their ghost blocks. */
    public void expire() {
        long now = System.currentTimeMillis();
        pending.forEach((key, operation) -> {
            if (operation.expiresAtMillis() >= now || !pending.remove(key, operation) || !operation.previewed()) return;
            Player player = Bukkit.getPlayer(key);
            if (player != null) ctx.scheduler.runForEntity(player, () -> revertPreview(player, operation), null);
        });
    }

    public int pendingCount() {
        return pending.size();
    }

    public boolean busy() {
        return busy.get();
    }

    // ── internals ──────────────────────────────────────────────────────────────────

    private LogQuery prepare(CommandSender sender, String raw, Origin origin) throws QuerySyntaxException {
        // defaultSeconds 0: a rollback must name its time itself, no silent default
        LogQuery q = QueryParser.parse(raw, nowSec(), LookupService::tag, 0);
        if (q.timeText() == null || (q.users().isEmpty() && q.radius().kind() == Radius.Kind.NONE)) {
            throw new QuerySyntaxException("rollback-scope", q.canonical());
        }
        if (QueryCompiler.categoryOf(q) == Category.CONTAINER && !sender.hasPermission("justlogs.lookup.container")) {
            throw new QuerySyntaxException("no-container", q.canonical());
        }
        boolean global = sender.hasPermission("justlogs.rollback.global");
        boolean local = q.radius().kind() == Radius.Kind.CUBE || q.radius().kind() == Radius.Kind.CHUNK;
        if (!local && !global) throw new QuerySyntaxException("no-global", q.canonical());
        if (q.radius().kind() == Radius.Kind.CUBE && q.radius().size() > ctx.settings().lookup().maxRadius() && !global) {
            throw new QuerySyntaxException("radius-too-large", String.valueOf(ctx.settings().lookup().maxRadius()));
        }
        if (q.radius().kind() == Radius.Kind.CUBE && q.at() == null && !origin.hasWorld()) {
            throw new QuerySyntaxException("need-position", q.canonical());
        }
        return q;
    }

    /** Decides between "run now", "wait for /jo confirm" and "preview first". Any thread. */
    private void plannedFromQuery(CommandSender sender, Plan plan, boolean restore, LogQuery query) {
        Messages m = ctx.messages();
        Settings.Rollback cfg = ctx.settings().rollback();
        boolean preview = query.has(LogQuery.Flag.PREVIEW);
        if (preview && plan.blocks() > cfg.previewMaxBlocks()) {
            send(sender, m.get("rollback.preview-too-large", Messages.text("max", String.valueOf(cfg.previewMaxBlocks()))));
            return;
        }
        if (!preview && plan.blocks() <= cfg.confirmAbove()) {
            execute(sender, plan, restore, query.canonical(),
                    restore ? RollbackStore.KIND_RESTORE : RollbackStore.KIND_ROLLBACK, null);
            return;
        }
        Runnable show = () -> {
            pending.put(LookupService.key(sender), new Pending(plan, restore, query,
                    System.currentTimeMillis() + cfg.confirmExpireSeconds() * 1000L, preview));
            if (preview && sender instanceof Player player && plan instanceof RollbackPlan blocks) showPreview(player, blocks);
            sender.sendMessage(m.get(preview ? "rollback.preview" : "rollback.confirm",
                    Messages.text("mode", m.raw(restore ? "rollback.word-restore" : "rollback.word-rollback")),
                    Messages.text("blocks", String.valueOf(plan.blocks())),
                    Messages.text("unit", m.raw(plan.containers() ? "rollback.unit-items" : "rollback.unit-blocks")),
                    Messages.text("chunks", String.valueOf(plan.chunkCount())),
                    Messages.text("query", query.canonical()),
                    Messages.text("seconds", String.valueOf(cfg.confirmExpireSeconds()))));
        };
        runFor(sender, show);
    }

    private void execute(CommandSender sender, Plan plan, boolean restore, String queryText, int kind,
                         @Nullable Long undoOf) {
        Messages m = ctx.messages();
        if (!busy.compareAndSet(false, true)) {
            send(sender, m.get("rollback.busy"));
            return;
        }
        String unit = m.raw(plan.containers() ? "rollback.unit-items" : "rollback.unit-blocks");
        send(sender, m.get("rollback.started", Messages.text("blocks", String.valueOf(plan.blocks())),
                Messages.text("unit", unit), Messages.text("chunks", String.valueOf(plan.chunkCount()))));
        long started = System.nanoTime();
        Actor actor = actor(sender);
        int source = sender instanceof Player ? RollbackStore.SOURCE_GAME : RollbackStore.SOURCE_CONSOLE;
        plan.apply(ctx.scheduler, ctx.settings().rollback().msPerTick())
                .thenCompose(result -> ctx.db.supply(c -> {
                    int userId = ctx.ids.user(c, actor, nowSec());
                    long ms = (System.nanoTime() - started) / 1_000_000;
                    store.record(c, userId, source, kind, queryText, result.changed(), result.chunks(), ms, undoOf,
                            plan.containers(), plan.rowIds(), nowSec());
                    return new Done(result, ms);
                }))
                .whenComplete((out, error) -> {
                    busy.set(false);
                    if (error != null) {
                        failed(sender, error);
                        return;
                    }
                    ApplyResult result = out.result();
                    long ms = out.ms();
                    send(sender, m.get(result.skipped() > 0 ? "rollback.done-skipped" : "rollback.done",
                            Messages.text("blocks", String.valueOf(result.changed())),
                            Messages.text("unit", unit),
                            Messages.text("chunks", String.valueOf(result.chunks())),
                            Messages.text("ms", String.valueOf(ms)),
                            Messages.text("skipped", String.valueOf(result.skipped()))));
                });
    }

    // ── preview ────────────────────────────────────────────────────────────────────

    /** Ghost blocks: only this player's client sees them. Must run on the player's thread. */
    private void showPreview(Player player, RollbackPlan plan) {
        Map<String, BlockData> cache = new HashMap<>();
        World world = player.getWorld();
        for (RollbackPlan.ChunkChanges chunk : plan.chunks()) {
            long key = Chunk.getChunkKey(chunk.chunkX(), chunk.chunkZ());
            if (!world.getName().equals(chunk.world()) || !player.isChunkSent(key)) continue;
            Map<Position, BlockData> changes = new HashMap<>();
            for (RollbackPlan.Change c : chunk.changes()) {
                BlockData data = BlockApplier.blockData(c, cache);
                if (data != null) changes.put(Position.block(c.x(), c.y(), c.z()), data);
            }
            if (!changes.isEmpty()) player.sendMultiBlockChange(changes);
        }
    }

    /** Reads the real blocks on their region threads and sends them on the player's thread. */
    private void revertPreview(CommandSender sender, Pending p) {
        if (!p.previewed() || !(sender instanceof Player player) || !(p.plan() instanceof RollbackPlan blocks)) return;
        World world = player.getWorld();
        for (RollbackPlan.ChunkChanges chunk : blocks.chunks()) {
            long key = Chunk.getChunkKey(chunk.chunkX(), chunk.chunkZ());
            if (!world.getName().equals(chunk.world()) || !player.isChunkSent(key)) continue;
            ctx.scheduler.runAtRegion(world, chunk.chunkX(), chunk.chunkZ(), () -> {
                if (!world.isChunkLoaded(chunk.chunkX(), chunk.chunkZ())) return;
                Map<Position, BlockData> changes = new HashMap<>();
                for (RollbackPlan.Change c : chunk.changes()) {
                    changes.put(Position.block(c.x(), c.y(), c.z()),
                            world.getBlockAt(c.x(), c.y(), c.z()).getBlockData());
                }
                ctx.scheduler.runForEntity(player, () -> {
                    if (player.getWorld().equals(world) && player.isChunkSent(key)) player.sendMultiBlockChange(changes);
                }, null);
            });
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private void failed(CommandSender sender, Throwable error) {
        Messages m = ctx.messages();
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof IllegalArgumentException wrapped && wrapped.getCause() instanceof QuerySyntaxException q) {
            send(sender, m.get("query.error." + q.code(), Messages.text("arg", q.arg())));
            return;
        }
        if (cause instanceof IllegalStateException) {
            send(sender, m.get("error.shutting-down"));
            return;
        }
        ctx.log.log(Level.WARNING, "Rollback failed", cause);
        send(sender, m.get("rollback.failed"));
    }

    private void send(CommandSender sender, Component line) {
        runFor(sender, () -> sender.sendMessage(line));
    }

    /** Runs on the thread that owns the sender (Folia safe). */
    private void runFor(CommandSender sender, Runnable task) {
        if (sender instanceof Player p) ctx.scheduler.runForEntity(p, task, null);
        else ctx.scheduler.runGlobal(task);
    }

    private static Actor actor(CommandSender sender) {
        return sender instanceof Player p ? Actor.player(p.getUniqueId(), p.getName()) : Actor.CONSOLE;
    }

    private static int nowSec() {
        return (int) (System.currentTimeMillis() / 1000);
    }
}
