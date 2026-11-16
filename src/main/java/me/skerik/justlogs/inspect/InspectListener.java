package me.skerik.justlogs.inspect;

import me.skerik.justlogs.Context;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Inspector clicks: left = the block itself, right = the spot next to it (where a block would be placed). */
public final class InspectListener implements Listener {

    private final Context ctx;

    public InspectListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!ctx.inspector.isInspecting(player.getUniqueId())) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        event.setCancelled(true); // never break, place or use anything while inspecting
        Block target = action == Action.LEFT_CLICK_BLOCK ? clicked : clicked.getRelative(event.getBlockFace());
        ctx.lookup.inspect(player, target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ctx.inspector.remove(event.getPlayer().getUniqueId());
        ctx.results.remove(event.getPlayer().getUniqueId());
    }
}
