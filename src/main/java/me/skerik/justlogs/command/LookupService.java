package me.skerik.justlogs.command;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.model.BlockEntry;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import me.skerik.justlogs.query.QueryParser;
import me.skerik.justlogs.query.QuerySyntaxException;
import me.skerik.justlogs.query.Radius;
import me.skerik.justlogs.storage.QueryCompiler;
import me.skerik.justlogs.util.Keys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Runs lookups for commands and the inspector: parse, check scope and permissions, wait until the writer has caught
 * up, query on the database executor, and send the page back on the sender's own thread.
 */
public final class LookupService {

    private record Outcome(QueryCompiler.@Nullable Page page, int total, @Nullable QuerySyntaxException error) {
    }

    private final Context ctx;

    public LookupService(Context ctx) {
        this.ctx = ctx;
    }

    // ── entry points ───────────────────────────────────────────────────────────────

    /** {@code /jo lookup <parameters>} (also used by {@code near}). Must be called on the sender's thread. */
    public void lookup(CommandSender sender, String raw) {
        Messages m = ctx.messages();
        Origin origin = origin(sender);
        LogQuery query;
        try {
            query = prepare(sender, raw, origin);
        } catch (QuerySyntaxException e) {
            sender.sendMessage(m.get("query.error." + e.code(), Messages.text("arg", e.arg())));
            return;
        }
        sender.sendMessage(m.get("lookup.searching"));
        run(sender, query, origin, false, "", 0, 0, 0);
    }

    /** Inspector click: the whole history of one block position, newest first. */
    public void inspect(Player player, String world, int x, int y, int z) {
        LogQuery query = new LogQuery(List.of(), false, 0, 0, null, Radius.cube(0), Set.of(), List.of(), List.of(), false,
                world, new int[] {x, y, z}, Set.of());
        run(player, query, Origin.NONE, true, world, x, y, z);
    }

    /** {@code /jo page n}: continues the sender's last result. */
    public void page(CommandSender sender, int page) {
        Messages m = ctx.messages();
        long ttl = ctx.settings().lookup().resultTtlSeconds() * 1000L;
        ResultCache.Entry entry = ctx.results.get(key(sender), ttl);
        if (entry == null) {
            sender.sendMessage(m.get("lookup.page-expired"));
            return;
        }
        if (page < 1 || (!entry.capped() && page > entry.pages())) {
            sender.sendMessage(m.get("lookup.page-range", Messages.text("pages", String.valueOf(entry.pages()))));
            return;
        }
        String cursorText = page == 1 ? null : entry.cursors().get(page);
        if (page > 1 && cursorText == null) {
            sender.sendMessage(m.get("lookup.page-unknown", Messages.text("page", String.valueOf(page))));
            return;
        }
        QueryCompiler.Cursor cursor = QueryCompiler.Cursor.decode(cursorText);
        ctx.writer.flushAll()
                .thenCompose(v -> ctx.db.supply(c -> {
                    try {
                        return new Outcome(ctx.compiler.lookup(c, entry.query(), entry.origin(), cursor, entry.pageSize()), entry.total(), null);
                    } catch (QuerySyntaxException e) {
                        return new Outcome(null, 0, e);
                    }
                }))
                .whenComplete((outcome, error) -> {
                    if (error != null || outcome == null || outcome.page() == null) {
                        failed(sender, error, outcome);
                        return;
                    }
                    QueryCompiler.Page result = outcome.page();
                    if (result.next() != null) entry.cursors().put(page + 1, result.next().encode());
                    send(sender, LookupRenderer.page(ctx.messages(), entry, result.rows(), page, result.next() != null,
                            nowSec(), entry.query().has(LogQuery.Flag.VERBOSE)));
                });
    }

    // ── internals ──────────────────────────────────────────────────────────────────

    private LogQuery prepare(CommandSender sender, String raw, Origin origin) throws QuerySyntaxException {
        Settings lookupSettings = new Settings(ctx);
        LogQuery q = QueryParser.parse(raw, nowSec(), LookupService::tag, lookupSettings.defaultTime());
        boolean unscoped = q.radius().kind() == Radius.Kind.NONE && q.users().isEmpty() && q.materials().isEmpty()
                && q.world() == null && q.at() == null;
        if (unscoped) {
            if (!(sender instanceof Player)) throw new QuerySyntaxException("need-scope", q.canonical());
            q = q.withRadius(Radius.cube(lookupSettings.defaultRadius()));
        }
        boolean global = sender.hasPermission("justlogs.lookup.global");
        if (q.radius().kind() == Radius.Kind.GLOBAL && !global) throw new QuerySyntaxException("no-global", q.canonical());
        if (q.radius().kind() == Radius.Kind.CUBE && q.radius().size() > lookupSettings.maxRadius() && !global) {
            throw new QuerySyntaxException("radius-too-large", String.valueOf(lookupSettings.maxRadius()));
        }
        if (q.radius().kind() == Radius.Kind.CUBE && q.at() == null && !origin.hasWorld()) {
            throw new QuerySyntaxException("need-position", q.canonical());
        }
        return q;
    }

    /** Tiny view of the lookup settings, read once per command. */
    private record Settings(long defaultTime, int defaultRadius, int maxRadius) {
        Settings(Context ctx) {
            this(ctx.settings().lookup().defaultTimeSeconds(), ctx.settings().lookup().defaultRadius(),
                    ctx.settings().lookup().maxRadius());
        }
    }

    private void run(CommandSender sender, LogQuery query, Origin origin, boolean spot, String world, int x, int y, int z) {
        int pageSize = ctx.settings().lookup().pageSize();
        int cap = ctx.settings().lookup().countCap();
        boolean countOnly = query.has(LogQuery.Flag.COUNT);
        ctx.writer.flushAll()
                .thenCompose(v -> ctx.db.supply(c -> {
                    try {
                        int total = ctx.compiler.count(c, query, origin, cap);
                        if (countOnly || total == 0) return new Outcome(new QueryCompiler.Page(List.of(), null), total, null);
                        return new Outcome(ctx.compiler.lookup(c, query, origin, null, pageSize), total, null);
                    } catch (QuerySyntaxException e) {
                        return new Outcome(null, 0, e);
                    }
                }))
                .whenComplete((outcome, error) -> {
                    if (error != null || outcome == null || outcome.page() == null) {
                        failed(sender, error, outcome);
                        return;
                    }
                    Messages m = ctx.messages();
                    QueryCompiler.Page page = outcome.page();
                    if (countOnly) {
                        send(sender, List.of(m.get("lookup.count", Messages.text("count", outcome.total() + (outcome.total() >= cap ? "+" : "")),
                                Messages.text("query", query.canonical()))));
                        return;
                    }
                    if (page.rows().isEmpty()) {
                        send(sender, List.of(spot
                                ? m.get("lookup.none-spot", Messages.text("x", String.valueOf(x)), Messages.text("y", String.valueOf(y)),
                                        Messages.text("z", String.valueOf(z)))
                                : m.get("lookup.none", Messages.text("query", query.canonical()))));
                        return;
                    }
                    ResultCache.Entry entry = new ResultCache.Entry(query, origin, outcome.total(), outcome.total() >= cap, pageSize,
                            spot, world, x, y, z, new ConcurrentHashMap<>(), System.currentTimeMillis());
                    if (page.next() != null) entry.cursors().put(2, page.next().encode());
                    ctx.results.put(key(sender), entry);
                    send(sender, LookupRenderer.page(m, entry, page.rows(), 1, page.next() != null, nowSec(),
                            query.has(LogQuery.Flag.VERBOSE)));
                });
    }

    private void failed(CommandSender sender, @Nullable Throwable error, @Nullable Outcome outcome) {
        Messages m = ctx.messages();
        if (outcome != null && outcome.error() != null) {
            send(sender, List.of(m.get("query.error." + outcome.error().code(), Messages.text("arg", outcome.error().arg()))));
            return;
        }
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof IllegalStateException) {
            send(sender, List.of(m.get("error.shutting-down")));
            return;
        }
        ctx.log.log(Level.WARNING, "Lookup failed", cause);
        send(sender, List.of(m.get("error.generic")));
    }

    /** Delivers on the thread that owns the recipient (Folia safe). */
    private void send(CommandSender sender, List<Component> lines) {
        Runnable task = () -> lines.forEach(sender::sendMessage);
        if (sender instanceof Player p) ctx.scheduler.runForEntity(p, task, null);
        else ctx.scheduler.runGlobal(task);
    }

    private static Origin origin(CommandSender sender) {
        if (sender instanceof Player p) {
            org.bukkit.Location l = p.getLocation();
            return new Origin(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }
        return Origin.NONE;
    }

    static UUID key(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : ResultCache.CONSOLE;
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    /** {@code b:#tag}: block tags first, then item tags. Material keys of the members. */
    private static Optional<Set<String>> tag(String name) {
        NamespacedKey key;
        try {
            key = NamespacedKey.minecraft(name);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
        if (tag == null) tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
        if (tag == null) return Optional.empty();
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (Material m : tag.getValues()) keys.add(Keys.material(m));
        return Optional.of(keys);
    }
}
