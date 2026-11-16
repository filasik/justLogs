package me.skerik.justlogs.listener;

import me.skerik.justlogs.Context;
import me.skerik.justlogs.config.Settings;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.util.Keys;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/**
 * Blocks placed and broken by players, and buckets. Runs at MONITOR so only changes that really happened are logged.
 * Only cheap work here (reading the state of the block); everything else happens on the writer thread.
 */
public final class PlayerBlockListener implements Listener {

    private final Context ctx;

    public PlayerBlockListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        if (!cfg.blockPlace()) return;
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        if (!cfg.worldLogged(world)) return;
        Actor actor = actor(event.getPlayer());
        if (event instanceof BlockMultiPlaceEvent multi) {
            // Beds, doors and tall plants place several blocks at once; each one is a row
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                offer(actor, Action.PLACE, world, replaced.getX(), replaced.getY(), replaced.getZ(),
                        replaced.getBlockData(), replaced.getBlock().getBlockData());
            }
            return;
        }
        offer(actor, Action.PLACE, world, block.getX(), block.getY(), block.getZ(),
                event.getBlockReplacedState().getBlockData(), block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        if (!cfg.blockBreak()) return;
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        if (!cfg.worldLogged(world)) return;
        // The event fires before the block disappears, so its data is still the old state
        offer(actor(event.getPlayer()), Action.BREAK, world, block.getX(), block.getY(), block.getZ(),
                block.getBlockData(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        if (!cfg.buckets()) return;
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        if (!cfg.worldLogged(world)) return;
        Material bucket = event.getBucket();
        String liquid = bucket == Material.LAVA_BUCKET ? "lava" : bucket == Material.POWDER_SNOW_BUCKET ? "powder_snow" : "water";
        String state = liquid.equals("powder_snow") ? "" : "level=0";
        BlockData old = block.getBlockData();
        enqueue(new BlockRecord(now(), actor(event.getPlayer()), Action.BUCKET_EMPTY, world, block.getX(), block.getY(),
                block.getZ(), Keys.material(old.getMaterial()), Keys.state(old), liquid, state));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        if (!cfg.buckets()) return;
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        if (!cfg.worldLogged(world)) return;
        offer(actor(event.getPlayer()), Action.BUCKET_FILL, world, block.getX(), block.getY(), block.getZ(),
                block.getBlockData(), null);
    }

    /** {@code after == null} means the block is now air. */
    private void offer(Actor actor, Action action, String world, int x, int y, int z, BlockData before, BlockData after) {
        String newMaterial = after == null ? "air" : Keys.material(after.getMaterial());
        String newState = after == null ? "" : Keys.state(after);
        enqueue(new BlockRecord(now(), actor, action, world, x, y, z,
                Keys.material(before.getMaterial()), Keys.state(before), newMaterial, newState));
    }

    private void enqueue(BlockRecord record) {
        ctx.queue.offer(record);
    }

    private static Actor actor(Player player) {
        return Actor.player(player.getUniqueId(), player.getName());
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }
}
