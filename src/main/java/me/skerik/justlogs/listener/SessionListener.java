package me.skerik.justlogs.listener;

import java.net.InetSocketAddress;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.SessionRecord;
import me.skerik.justlogs.util.IpHmac;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

/** Logs player logins and logouts with HMAC-SHA256 hashed IP addresses. */
public final class SessionListener implements Listener {

    private final Context ctx;

    public SessionListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer(), Action.LOGIN);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        record(event.getPlayer(), Action.LOGOUT);
    }

    private void record(Player player, Action action) {
        if (!ctx.settings().logging().sessions()) return;
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;

        String rawIp = extractIp(player);
        String ipHash = rawIp == null ? null : IpHmac.hashIp(ctx.secrets.hmacKey(), rawIp);
        String ip = ctx.settings().logging().logRawIp() ? rawIp : null;

        ctx.queue.offer(new SessionRecord((int) (System.currentTimeMillis() / 1000),
                Actor.player(player.getUniqueId(), player.getName()), action, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), ipHash, ip));
    }

    private static @Nullable String extractIp(Player player) {
        InetSocketAddress addr = player.getAddress();
        if (addr == null || addr.getAddress() == null) return null;
        return IpHmac.normalizeIp(addr.getAddress().getHostAddress());
    }
}
