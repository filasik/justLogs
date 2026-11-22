package me.skerik.justlogs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.JustLogs;
import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.storage.DbConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /justlogs and its subcommands (Brigadier). Aliases come from config.yml; {@code /jo} is the short form. */
public final class MainCommand {

    private final JustLogs plugin;

    public MainCommand(JustLogs plugin) {
        this.plugin = plugin;
    }

    private static Predicate<CommandSourceStack> has(String permission) {
        return src -> src.getSender().hasPermission(permission);
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralCommandNode<CommandSourceStack> inspect = Commands.literal("inspect")
                .requires(has("justlogs.inspect"))
                .executes(c -> player(c, (ctx, p) -> toggleInspect(ctx, p)))
                .build();
        LiteralCommandNode<CommandSourceStack> lookup = Commands.literal("lookup")
                .requires(has("justlogs.lookup"))
                .then(Commands.argument("parameters", StringArgumentType.greedyString())
                        .suggests(QuerySuggestions.provider())
                        .executes(c -> ready(c, (ctx, s) -> ctx.lookup.lookup(s, StringArgumentType.getString(c, "parameters")))))
                .build();
        LiteralCommandNode<CommandSourceStack> page = Commands.literal("page")
                .requires(has("justlogs.lookup"))
                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                        .executes(c -> ready(c, (ctx, s) -> ctx.lookup.page(s, IntegerArgumentType.getInteger(c, "n")))))
                .build();

        LiteralCommandNode<CommandSourceStack> rollback = Commands.literal("rollback")
                .requires(has("justlogs.rollback"))
                .then(Commands.argument("parameters", StringArgumentType.greedyString())
                        .suggests(QuerySuggestions.provider())
                        .executes(c -> ready(c, (ctx, s) -> ctx.rollback.start(s, StringArgumentType.getString(c, "parameters"), false))))
                .build();
        LiteralCommandNode<CommandSourceStack> restore = Commands.literal("restore")
                .requires(has("justlogs.restore"))
                .then(Commands.argument("parameters", StringArgumentType.greedyString())
                        .suggests(QuerySuggestions.provider())
                        .executes(c -> ready(c, (ctx, s) -> ctx.rollback.start(s, StringArgumentType.getString(c, "parameters"), true))))
                .build();
        Predicate<CommandSourceStack> changesWorld = src -> src.getSender().hasPermission("justlogs.rollback")
                || src.getSender().hasPermission("justlogs.restore");

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("justlogs")
                .requires(has("justlogs.use"))
                .executes(this::info)
                .then(Commands.literal("help").executes(c -> help(c, false))
                        .then(Commands.literal("params").executes(c -> help(c, true))))
                .then(inspect).then(alias("i", inspect, "justlogs.inspect"))
                .then(lookup).then(alias("l", lookup, "justlogs.lookup"))
                .then(page).then(alias("p", page, "justlogs.lookup"))
                .then(rollback).then(alias("rb", rollback, "justlogs.rollback"))
                .then(restore).then(alias("rs", restore, "justlogs.restore"))
                .then(Commands.literal("undo").requires(has("justlogs.rollback"))
                        .executes(c -> ready(c, (ctx, s) -> ctx.rollback.undo(s))))
                .then(Commands.literal("confirm").requires(changesWorld)
                        .executes(c -> ready(c, (ctx, s) -> ctx.rollback.confirm(s))))
                .then(Commands.literal("cancel").requires(changesWorld)
                        .executes(c -> ready(c, (ctx, s) -> ctx.rollback.cancel(s))))
                .then(Commands.literal("near")
                        .requires(has("justlogs.lookup"))
                        .executes(c -> ready(c, (ctx, s) -> ctx.lookup.lookup(s, "r:" + ctx.settings().lookup().defaultRadius() + " t:3d")))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                .executes(c -> ready(c, (ctx, s) -> ctx.lookup.lookup(s,
                                        "r:" + IntegerArgumentType.getInteger(c, "radius") + " t:3d")))))
                .then(Commands.literal("tp")
                        .requires(has("justlogs.teleport"))
                        .then(Commands.argument("world", StringArgumentType.word())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(c -> player(c, (ctx, p) -> teleport(ctx, p,
                                                                StringArgumentType.getString(c, "world"),
                                                                IntegerArgumentType.getInteger(c, "x"),
                                                                IntegerArgumentType.getInteger(c, "y"),
                                                                IntegerArgumentType.getInteger(c, "z")))))))))
                .then(Commands.literal("status").requires(has("justlogs.status"))
                        .executes(c -> ready(c, (ctx, s) -> status(ctx, s))))
                .then(Commands.literal("purge").requires(has("justlogs.purge"))
                        .then(Commands.literal("confirm")
                                .executes(c -> ready(c, (ctx, s) -> ctx.purge.confirm(s))))
                        .then(Commands.argument("parameters", StringArgumentType.greedyString())
                                .executes(c -> ready(c, (ctx, s) -> ctx.purge.start(s, StringArgumentType.getString(c, "parameters"))))))
                .then(Commands.literal("reload").requires(has("justlogs.reload")).executes(this::reload));
        return root.build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> alias(String name, LiteralCommandNode<CommandSourceStack> target,
                                                                    String permission) {
        return Commands.literal(name).requires(has(permission)).redirect(target);
    }

    private int info(CommandContext<CommandSourceStack> c) {
        c.getSource().getSender().sendMessage(plugin.messages().get("command.info",
                Messages.text("version", plugin.getPluginMeta().getVersion())));
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> c, boolean params) {
        CommandSender sender = c.getSource().getSender();
        Messages m = plugin.messages();
        if (params) {
            sender.sendMessage(m.get("help.params-header"));
            m.list("help.params").forEach(sender::sendMessage);
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(m.get("help.header"));
        for (String key : new String[] {"inspect", "lookup", "near", "page", "rollback", "restore", "undo", "confirm", "purge", "status", "reload"}) {
            // Only the commands this sender may use
            String permission = switch (key) {
                case "inspect" -> "justlogs.inspect";
                case "status" -> "justlogs.status";
                case "purge" -> "justlogs.purge";
                case "reload" -> "justlogs.reload";
                case "rollback", "undo", "confirm" -> "justlogs.rollback";
                case "restore" -> "justlogs.restore";
                default -> "justlogs.lookup";
            };
            if (sender.hasPermission(permission)) {
                sender.sendMessage(m.get("help." + key, Messages.text("parameters", "<parameters>"), Messages.text("n", "<n>")));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> c) {
        plugin.reloadSettings();
        c.getSource().getSender().sendMessage(plugin.messages().get("command.reloaded"));
        return Command.SINGLE_SUCCESS;
    }

    private void toggleInspect(Context ctx, Player player) {
        boolean on = ctx.inspector.toggle(player.getUniqueId());
        player.sendMessage(ctx.messages().get(on ? "inspect.enabled" : "inspect.disabled"));
    }

    private void teleport(Context ctx, Player player, String worldName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(ctx.messages().get("tp.failed", Messages.text("world", worldName)));
            return;
        }
        player.teleportAsync(new Location(world, x + 0.5, y, z + 0.5)).thenRun(() ->
                ctx.scheduler.runForEntity(player, () -> player.sendMessage(ctx.messages().get("tp.done",
                        Messages.text("x", String.valueOf(x)), Messages.text("y", String.valueOf(y)),
                        Messages.text("z", String.valueOf(z)))), null));
    }

    private void status(Context ctx, CommandSender sender) {
        Messages m = ctx.messages();
        long now = System.currentTimeMillis();
        sender.sendMessage(m.get("status.header", Messages.text("version", plugin.getPluginMeta().getVersion())));
        sender.sendMessage(m.get("status.database", Messages.text("type",
                ctx.db.dialect() == DbConfig.Dialect.SQLITE ? "SQLite" : "MariaDB"),
                Messages.text("schema", String.valueOf(ctx.schemaVersion))));
        sender.sendMessage(m.get("status.queue", Messages.text("size", String.valueOf(ctx.queue.size())),
                Messages.text("limit", String.valueOf(ctx.queue.softLimit())),
                Messages.text("dropped", String.valueOf(ctx.queue.dropped()))));
        long spooled = ctx.writer.spooledCount();
        if (spooled > 0) sender.sendMessage(m.get("status.spool", Messages.text("count", String.valueOf(spooled))));
        sender.sendMessage(m.get("status.writer",
                Messages.text("rate", String.format("%.1f", ctx.writer.throughput().perSecond(now))),
                Messages.text("ms", String.valueOf(ctx.writer.lastFlushMillis())),
                Messages.text("written", String.valueOf(ctx.writer.written()))));
        String error = ctx.writer.lastError();
        if (error != null) sender.sendMessage(m.get("status.writer-error", Messages.text("error", error)));
        sender.sendMessage(m.get("status.rollback", Messages.text("state", m.raw(ctx.rollback.busy() ? "status.state-busy" : "status.state-idle")),
                Messages.text("pending", String.valueOf(ctx.rollback.pendingCount()))));
        sender.sendMessage(m.get("status.inspecting", Messages.text("count", String.valueOf(ctx.inspector.count()))));
    }

    /** Runs an action that needs the started plugin; answers "not ready" before startup has finished. */
    private int ready(CommandContext<CommandSourceStack> c, BiConsumer<Context, CommandSender> action) {
        CommandSender sender = c.getSource().getSender();
        Context ctx = plugin.context();
        if (ctx == null) {
            sender.sendMessage(plugin.messages().get("error.not-ready"));
            return 0;
        }
        action.accept(ctx, sender);
        return Command.SINGLE_SUCCESS;
    }

    /** Like {@link #ready} for commands that need a player: "not ready" first, "players only" for the console. */
    private int player(CommandContext<CommandSourceStack> c, BiConsumer<Context, Player> action) {
        CommandSender sender = c.getSource().getSender();
        Context ctx = plugin.context();
        if (ctx == null) {
            sender.sendMessage(plugin.messages().get("error.not-ready"));
            return 0;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ctx.messages().get("error.players-only"));
            return 0;
        }
        action.accept(ctx, p);
        return Command.SINGLE_SUCCESS;
    }

}
