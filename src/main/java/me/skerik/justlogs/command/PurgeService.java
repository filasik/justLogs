package me.skerik.justlogs.command;

import java.sql.PreparedStatement;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.Category;
import me.skerik.justlogs.query.ActionGroups;
import me.skerik.justlogs.storage.Migrations;
import me.skerik.justlogs.storage.Purger;
import me.skerik.justlogs.util.Durations;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** Handles {@code /jo purge} (with confirmation) and periodic automatic retention. */
public final class PurgeService {

    private static final UUID CONSOLE_KEY = new UUID(0L, 0L);
    private static final long CONFIRM_TTL_MS = 60_000L;

    private record Pending(int cutoffSec, Set<Category> categories, @Nullable String world, boolean optimize,
                           String summary, long expiresAtMillis) {
    }

    private final Context ctx;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();

    public PurgeService(Context ctx) {
        this.ctx = ctx;
    }

    public void start(CommandSender sender, String raw) {
        Messages m = ctx.messages();
        long ageSec = -1;
        String timeText = null;
        Set<Category> categories = EnumSet.noneOf(Category.class);
        String world = null;
        boolean optimize = false;

        for (String token : raw.strip().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.equalsIgnoreCase("#optimize")) {
                optimize = true;
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0) {
                sender.sendMessage(m.get("query.error.unknown-param", Messages.text("arg", token)));
                return;
            }
            String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String val = token.substring(colon + 1);
            if (val.isEmpty()) {
                sender.sendMessage(m.get("query.error.missing-value", Messages.text("arg", key + ":")));
                return;
            }
            switch (key) {
                case "t" -> {
                    var parsed = Durations.parseSeconds(val);
                    if (parsed.isEmpty()) {
                        sender.sendMessage(m.get("query.error.bad-time", Messages.text("arg", val)));
                        return;
                    }
                    ageSec = parsed.getAsLong();
                    timeText = val.toLowerCase(Locale.ROOT);
                }
                case "a" -> {
                    for (String part : val.split(",")) {
                        var resolved = ActionGroups.resolve(part);
                        if (resolved.isEmpty()) {
                            sender.sendMessage(m.get("query.error.unknown-action", Messages.text("arg", part)));
                            return;
                        }
                        for (Action a : resolved.get()) categories.add(a.category());
                    }
                }
                case "w" -> world = val;
                default -> {
                    sender.sendMessage(m.get("query.error.unknown-param", Messages.text("arg", token)));
                    return;
                }
            }
        }
        if (ageSec <= 0 || timeText == null) {
            sender.sendMessage(m.get("purge.need-time"));
            return;
        }
        int cutoff = (int) (System.currentTimeMillis() / 1000 - ageSec);
        String summary = "t:" + timeText
                + (categories.isEmpty() ? "" : " a:" + categories.size() + "cat")
                + (world == null ? "" : " w:" + world)
                + (optimize ? " #optimize" : "");
        pending.put(key(sender), new Pending(cutoff, Set.copyOf(categories), world, optimize, summary,
                System.currentTimeMillis() + CONFIRM_TTL_MS));
        sender.sendMessage(m.get("purge.confirm", Messages.text("summary", summary)));
    }

    public void confirm(CommandSender sender) {
        Messages m = ctx.messages();
        Pending p = pending.remove(key(sender));
        if (p == null || p.expiresAtMillis() < System.currentTimeMillis()) {
            sender.sendMessage(m.get("purge.nothing-pending"));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage(m.get("purge.busy"));
            return;
        }
        sender.sendMessage(m.get("purge.running"));
        Actor actor = sender instanceof Player pl
                ? Actor.player(pl.getUniqueId(), pl.getName())
                : Actor.CONSOLE;

        ctx.writer.flushAll()
                .thenCompose(v -> ctx.db.supply(c -> {
                    Purger.Result res = Purger.purge(c, ctx.ids, ctx.db.dialect(), p.cutoffSec(), p.categories(),
                            p.world(), p.optimize());
                    int uid = ctx.ids.user(c, actor, (int) (System.currentTimeMillis() / 1000));
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + Migrations.table("audit")
                            + " (time, user_id, source, action, detail) VALUES (?, ?, 0, 'purge', ?)")) {
                        ps.setInt(1, (int) (System.currentTimeMillis() / 1000));
                        ps.setInt(2, uid);
                        ps.setString(3, p.summary() + " -> " + res.deleted() + " rows");
                        ps.executeUpdate();
                    }
                    return res;
                }))
                .whenComplete((res, err) -> {
                    busy.set(false);
                    if (err != null) {
                        ctx.log.log(Level.WARNING, "Purge failed", err);
                        ctx.scheduler.runGlobal(() -> sender.sendMessage(ctx.messages().get("error.database")));
                        return;
                    }
                    ctx.scheduler.runGlobal(() -> sender.sendMessage(ctx.messages().get("purge.done",
                            Messages.text("count", String.valueOf(res.deleted())),
                            Messages.text("ms", String.valueOf(res.durationMs())))));
                });
    }

    /** Automatic retention task called periodically from a background timer. */
    public void runRetention() {
        int days = ctx.settings().purge().retentionDays();
        if (days <= 0) return;
        if (!busy.compareAndSet(false, true)) return;
        int cutoff = (int) (System.currentTimeMillis() / 1000 - days * 86_400L);
        ctx.db.supply(c -> Purger.purge(c, ctx.ids, ctx.db.dialect(), cutoff, Set.of(), null, false))
                .whenComplete((res, err) -> {
                    busy.set(false);
                    if (err != null) {
                        ctx.log.log(Level.WARNING, "Automatic retention purge failed", err);
                    } else if (res != null && res.deleted() > 0) {
                        ctx.log.info("Retention purge removed " + res.deleted() + " rows older than " + days
                                + " days in " + res.durationMs() + " ms");
                    }
                });
    }

    private static UUID key(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : CONSOLE_KEY;
    }
}
