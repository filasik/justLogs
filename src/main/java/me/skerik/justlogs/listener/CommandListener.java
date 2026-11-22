package me.skerik.justlogs.listener;

import java.util.Locale;
import java.util.Set;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.CommandRecord;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Logs commands executed by players. Authentication commands have their arguments redacted so passwords never enter
 * the queue or database, and blacklisted commands are skipped completely.
 */
public final class CommandListener implements Listener {

    private static final Set<String> REDACT = Set.of(
            "login", "l", "register", "reg", "changepassword", "changepass", "2fa", "totp", "email", "auth");

    private final Context ctx;

    public CommandListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!ctx.settings().logging().commands()) return;
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;

        String raw = event.getMessage().strip();
        if (raw.isEmpty()) return;
        String label = baseLabel(raw);
        if (ctx.settings().logging().commandBlacklist().contains(label)) return;
        String logged = REDACT.contains(label) && raw.indexOf(' ') > 0 ? "/" + label + " ***" : raw;

        ctx.queue.offer(new CommandRecord((int) (System.currentTimeMillis() / 1000),
                Actor.player(player.getUniqueId(), player.getName()), world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), logged));
    }

    static String baseLabel(String command) {
        String s = command.startsWith("/") ? command.substring(1) : command;
        int space = s.indexOf(' ');
        if (space > 0) s = s.substring(0, space);
        int colon = s.indexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) s = s.substring(colon + 1);
        return s.toLowerCase(Locale.ROOT);
    }
}
