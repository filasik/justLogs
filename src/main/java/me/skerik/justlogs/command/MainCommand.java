package me.skerik.justlogs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.JustLogs;
import me.skerik.justlogs.config.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.function.BiConsumer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /justlogs and its subcommands (Brigadier). Aliases come from config.yml. */
public final class MainCommand {

    private final JustLogs plugin;

    public MainCommand(JustLogs plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("justlogs")
                .requires(src -> src.getSender().hasPermission("justlogs.use"))
                .executes(this::info)
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("justlogs.admin"))
                        .executes(this::reload))
                .build();
    }

    private int info(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        sender.sendMessage(plugin.messages().get("command.info",
                Messages.text("version", plugin.getPluginMeta().getVersion())));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        plugin.reloadSettings();
        sender.sendMessage(plugin.messages().get("command.reloaded"));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Pattern for commands that need the started plugin and a player: answers "not ready" before startup has
     * finished and "players only" for the console. Use it for every command that touches the services.
     */
    @SuppressWarnings("unused") // template helper: wire it to the first real player command
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
