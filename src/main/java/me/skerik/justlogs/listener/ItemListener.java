package me.skerik.justlogs.listener;

import me.skerik.justlogs.Context;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.ItemRecord;
import me.skerik.justlogs.util.Items;
import me.skerik.justlogs.util.Keys;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

/** Logs items dropped, picked up and crafted by players. */
public final class ItemListener implements Listener {

    private final Context ctx;

    public ItemListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!ctx.settings().logging().itemDrops()) return;
        record(event.getPlayer(), Action.DROP, event.getItemDrop().getItemStack(), event.getItemDrop().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!ctx.settings().logging().itemPickups()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        record(player, Action.PICKUP, event.getItem().getItemStack(), event.getItem().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!ctx.settings().logging().itemCrafts()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) return;
        record(player, Action.CRAFT, result, player.getLocation());
    }

    private void record(Player player, Action action, ItemStack stack, Location loc) {
        if (stack.getType().isAir() || stack.getAmount() <= 0) return;
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        ctx.queue.offer(new ItemRecord((int) (System.currentTimeMillis() / 1000),
                Actor.player(player.getUniqueId(), player.getName()), action, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.material(stack.getType()), stack.getAmount(), Items.dataOf(stack)));
    }
}
