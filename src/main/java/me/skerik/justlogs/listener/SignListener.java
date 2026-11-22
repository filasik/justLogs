package me.skerik.justlogs.listener;

import java.util.List;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.SignRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

/** Text written on signs. MONITOR, so the sign still holds the old text while the event carries the final new text. */
public final class SignListener implements Listener {

    private final Context ctx;

    public SignListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!ctx.settings().logging().signs()) return;
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        Side side = event.getSide();
        String oldText = "";
        BlockState state = block.getState(false);
        if (state instanceof Sign sign) oldText = join(sign.getSide(side).lines());
        String newText = join(event.lines());
        if (oldText.equals(newText)) return;
        ctx.queue.offer(new SignRecord((int) (System.currentTimeMillis() / 1000),
                Actor.player(event.getPlayer().getUniqueId(), event.getPlayer().getName()), Action.SIGN_EDIT, world,
                block.getX(), block.getY(), block.getZ(), side == Side.BACK ? 1 : 0, oldText, newText));
    }

    private static String join(List<Component> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(PlainTextComponentSerializer.plainText().serialize(lines.get(i)));
        }
        String s = sb.toString();
        return s.isBlank() ? "" : s;
    }
}
