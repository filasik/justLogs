package me.skerik.justlogs.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.ChatRecord;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Logs player chat messages via Paper's {@link AsyncChatEvent}. */
public final class ChatListener implements Listener {

    private final Context ctx;

    public ChatListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!ctx.settings().logging().chat()) return;
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (text.isEmpty()) return;
        ctx.queue.offer(new ChatRecord((int) (System.currentTimeMillis() / 1000),
                Actor.player(player.getUniqueId(), player.getName()), world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), text));
    }
}
