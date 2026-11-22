package me.skerik.justlogs.listener;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.config.Settings;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.util.Keys;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

    private static final BlockFace[] ADJACENT = {
            BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final Context ctx;
    private final CauseTracker causes;

    public PlayerBlockListener(Context ctx, CauseTracker causes) {
        this.ctx = ctx;
        this.causes = causes;
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
        Block block = event.getBlock();
        Actor actor = actor(event.getPlayer());
        causes.recentBreak(block, actor);
        if (!cfg.blockBreak()) return;
        String world = block.getWorld().getName();
        if (!cfg.worldLogged(world)) return;
        // The event fires before the block disappears, so its data is still the old state
        BlockData data = block.getBlockData();
        enqueue(new BlockRecord(now(), actor, Action.BREAK, world, block.getX(), block.getY(), block.getZ(),
                Keys.material(data.getMaterial()), Keys.state(data), "air", "", Containers.snapshot(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(BlockDestroyEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        if (!cfg.blockBreak()) return;
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        if (!cfg.worldLogged(world)) return;
        Actor actor = null;
        for (BlockFace face : ADJACENT) {
            actor = causes.recentBreakOwner(block.getRelative(face));
            if (actor != null) break;
        }
        if (actor == null) actor = Actor.source("#nature");
        causes.recentBreak(block, actor); // chain for multi-block plants (sugar cane, bamboo, cactus)
        BlockData before = block.getBlockData();
        BlockData after = event.getNewState();
        enqueue(new BlockRecord(now(), actor, Action.BREAK, world, block.getX(), block.getY(), block.getZ(),
                Keys.material(before.getMaterial()), Keys.state(before),
                Keys.material(after.getMaterial()), Keys.state(after), Containers.snapshot(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        Block block = event.getBlock();
        Actor actor = actor(event.getPlayer());
        Material bucket = event.getBucket();
        if (bucket == Material.WATER_BUCKET || bucket == Material.LAVA_BUCKET) causes.fluid(block, actor);
        if (!cfg.buckets()) return;
        String world = block.getWorld().getName();
        if (!cfg.worldLogged(world)) return;
        String liquid = bucket == Material.LAVA_BUCKET ? "lava" : bucket == Material.POWDER_SNOW_BUCKET ? "powder_snow" : "water";
        String state = liquid.equals("powder_snow") ? "" : "level=0";
        BlockData old = block.getBlockData();
        enqueue(new BlockRecord(now(), actor, Action.BUCKET_EMPTY, world, block.getX(), block.getY(),
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
