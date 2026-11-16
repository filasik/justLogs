package me.skerik.justlogs.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.ContainerRecord;
import me.skerik.justlogs.util.Items;
import me.skerik.justlogs.util.Keys;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Item moves in and out of containers. Instead of interpreting every kind of click, it keeps a snapshot of the container
 * from the moment a player opens it and compares it after each click (one tick later, when the server has applied it) and
 * on close. The net change per item type becomes one deposit or withdraw record, so shift-clicks, drags and number keys
 * all work the same way.
 */
public final class ContainerListener implements Listener {

    private static final class Session {
        final Block block;
        ItemStack[] before;

        Session(Block block, ItemStack[] before) {
            this.block = block;
            this.before = before;
        }
    }

    private record Tally(ItemStack one, int[] delta) {
    }

    private final Context ctx;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ContainerListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!ctx.settings().logging().containers() || !(event.getPlayer() instanceof Player player)) return;
        Block block = Containers.blockOf(event.getInventory());
        if (block == null || !ctx.settings().logging().worldLogged(block.getWorld().getName())) return;
        sessions.put(player.getUniqueId(), new Session(block, copy(event.getInventory().getContents())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        schedule(event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        schedule(event.getWhoClicked());
    }

    private void schedule(org.bukkit.entity.HumanEntity who) {
        if (!(who instanceof Player player) || !sessions.containsKey(player.getUniqueId())) return;
        ctx.scheduler.runForEntity(player, () -> {
            Inventory top = player.getOpenInventory().getTopInventory();
            diff(player, top);
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        diff(player, event.getInventory());
        sessions.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void diff(Player player, Inventory top) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        Block block = Containers.blockOf(top);
        if (block == null || !block.equals(session.block)) return; // a different inventory is open now
        ItemStack[] after = copy(top.getContents());
        List<Tally> tallies = new ArrayList<>();
        for (ItemStack s : session.before) add(tallies, s, -1);
        for (ItemStack s : after) add(tallies, s, 1);
        session.before = after;

        Actor actor = Actor.player(player.getUniqueId(), player.getName());
        int now = (int) (System.currentTimeMillis() / 1000);
        String holder = Keys.material(block.getType());
        for (Tally t : tallies) {
            int delta = t.delta()[0];
            if (delta == 0) continue;
            ctx.queue.offer(new ContainerRecord(now, actor, delta > 0 ? Action.DEPOSIT : Action.WITHDRAW,
                    block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), holder,
                    Keys.material(t.one().getType()), Math.abs(delta), Items.dataOf(t.one())));
        }
    }

    /** Adds {@code sign * amount} to the tally of this item type (items that are "similar" count as one type). */
    private static void add(List<Tally> tallies, ItemStack stack, int sign) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return;
        for (Tally t : tallies) {
            if (t.one().isSimilar(stack)) {
                t.delta()[0] += sign * stack.getAmount();
                return;
            }
        }
        tallies.add(new Tally(stack.asOne(), new int[] {sign * stack.getAmount()}));
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) out[i] = contents[i] == null ? null : contents[i].clone();
        return out;
    }
}
