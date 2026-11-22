package me.skerik.justlogs.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.InteractRecord;
import me.skerik.justlogs.util.Keys;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Bell;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Logs player interactions with doors, buttons, levers, pressure plates, note blocks and bells, debounced per
 * player and block position so holding right-click or standing on a plate does not flood the database.
 */
public final class InteractListener implements Listener {

    private static final long DEBOUNCE_MS = 1000L;

    private record Key(UUID player, String world, int x, int y, int z) {
    }

    private final Context ctx;
    private final Map<Key, Long> recent = new ConcurrentHashMap<>();

    public InteractListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!ctx.settings().logging().interactions()) return;
        if (event.useInteractedBlock() == Event.Result.DENY) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        org.bukkit.event.block.Action click = event.getAction();
        if (click == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (!isInteractive(block.getBlockData())) return;
        } else if (click != org.bukkit.event.block.Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        if (ctx.inspector.isInspecting(player.getUniqueId())) return;
        String world = block.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;

        long nowMs = System.currentTimeMillis();
        Key key = new Key(player.getUniqueId(), world, block.getX(), block.getY(), block.getZ());
        Long prev = recent.get(key);
        if (prev != null && nowMs - prev < DEBOUNCE_MS) return;
        if (recent.size() > 4096) recent.clear();
        recent.put(key, nowMs);

        ctx.queue.offer(new InteractRecord((int) (nowMs / 1000),
                Actor.player(player.getUniqueId(), player.getName()), Action.CLICK, world,
                block.getX(), block.getY(), block.getZ(), Keys.material(block.getType())));
    }

    private static boolean isInteractive(BlockData data) {
        return data instanceof Openable || data instanceof Switch || data instanceof Powerable
                || data instanceof NoteBlock || data instanceof Bell;
    }
}
