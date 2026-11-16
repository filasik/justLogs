package me.skerik.justlogs.listener;

import java.util.List;
import java.util.Locale;
import me.skerik.justlogs.Context;
import me.skerik.justlogs.config.Settings;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.util.Keys;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Changes that are not a player placing or breaking a block: fire, explosions, growth, mobs. The cause is a player when
 * one can be named (via {@link CauseTracker}), otherwise a source such as {@code #fire}, {@code #creeper}, {@code #nature}.
 */
public final class NatureListener implements Listener {

    private final Context ctx;
    private final CauseTracker causes;

    public NatureListener(Context ctx, CauseTracker causes) {
        this.ctx = ctx;
        this.causes = causes;
    }

    // ── fire ───────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        Block block = event.getBlock();
        Actor actor;
        if (event.getPlayer() != null) {
            actor = player(event.getPlayer());
        } else {
            Actor inherited = causes.fireOwner(event.getIgnitingBlock());
            if (inherited != null) actor = inherited;
            else if (event.getIgnitingEntity() instanceof Player p) actor = player(p);
            else actor = switch (event.getCause()) {
                case LAVA -> Actor.LAVA;
                case LIGHTNING -> Actor.source("#lightning");
                default -> Actor.FIRE;
            };
        }
        // Remember the owner for the fire that will burn here; tracking costs nothing even when logging is off
        causes.fire(block, actor);
        if (!cfg.fire() || !cfg.worldLogged(block.getWorld().getName())) return;
        enqueue(actor, Action.IGNITE, block, block.getBlockData(), "fire", "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        Block block = event.getBlock();
        if (!cfg.fire() || !cfg.worldLogged(block.getWorld().getName())) return;
        Actor actor = causes.fireOwner(event.getIgnitingBlock());
        if (actor == null) actor = Actor.FIRE;
        enqueue(actor, Action.BURN, block, block.getBlockData(), "air", "");
    }

    // ── explosions ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;
        Entity source = tnt.getSource();
        if (source instanceof Player p) causes.tnt(tnt.getUniqueId(), player(p));
        else if (source instanceof TNTPrimed chain) {
            Actor parent = causes.tntOwner(chain.getUniqueId());
            if (parent != null) causes.tnt(tnt.getUniqueId(), parent);
        } else {
            Actor fire = causes.fireOwner(tnt.getLocation().getBlock());
            if (fire != null) causes.tnt(tnt.getUniqueId(), fire);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        if (!cfg.explosions() || event.blockList().isEmpty()) return;
        Entity entity = event.getEntity();
        Actor actor;
        if (entity instanceof TNTPrimed tnt) {
            Actor owner = causes.tntOwner(tnt.getUniqueId());
            actor = owner != null ? owner : Actor.TNT;
        } else {
            actor = Actor.source("#" + entity.getType().name().toLowerCase(Locale.ROOT));
        }
        explode(actor, event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!ctx.settings().logging().explosions() || event.blockList().isEmpty()) return;
        explode(Actor.source("#explosion"), event.blockList());
    }

    private void explode(Actor actor, List<Block> blocks) {
        Settings.Logging cfg = ctx.settings().logging();
        for (Block block : blocks) {
            if (block.getType().isAir() || !cfg.worldLogged(block.getWorld().getName())) continue;
            BlockData before = block.getBlockData();
            ctx.queue.offer(new BlockRecord((int) (System.currentTimeMillis() / 1000), actor, Action.EXPLODE,
                    block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), Keys.material(before.getMaterial()),
                    Keys.state(before), "air", "", Containers.snapshot(block)));
        }
    }

    // ── growth, decay, mobs ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        if (!cfg.growth() || !cfg.worldLogged(event.getWorld().getName())) return;
        Actor actor = event.getPlayer() != null ? player(event.getPlayer()) : Actor.source("#nature");
        for (BlockState state : event.getBlocks()) {
            Block block = state.getBlock();
            BlockData before = block.getBlockData();
            BlockData after = state.getBlockData();
            if (before.equals(after)) continue;
            enqueue(actor, Action.GROW, block, before, Keys.material(after.getMaterial()), Keys.state(after));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        Block block = event.getBlock();
        if (!cfg.leafDecay() || !cfg.worldLogged(block.getWorld().getName())) return;
        enqueue(Actor.source("#nature"), Action.DECAY, block, block.getBlockData(), "air", "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        Settings.Logging cfg = ctx.settings().logging();
        Block block = event.getBlock();
        // Falling blocks are a separate logger (planned); here only mobs and players that change a block
        if (!cfg.entityChanges() || event.getEntity() instanceof FallingBlock || !cfg.worldLogged(block.getWorld().getName())) return;
        Entity entity = event.getEntity();
        Actor actor = entity instanceof Player p ? player(p)
                : Actor.source("#" + entity.getType().name().toLowerCase(Locale.ROOT));
        BlockData after = event.getBlockData();
        enqueue(actor, Action.ENTITY_CHANGE, block, block.getBlockData(), Keys.material(after.getMaterial()), Keys.state(after));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private void enqueue(Actor actor, Action action, Block block, BlockData before, String newMaterial, String newState) {
        ctx.queue.offer(new BlockRecord((int) (System.currentTimeMillis() / 1000), actor, action, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), Keys.material(before.getMaterial()), Keys.state(before),
                newMaterial, newState));
    }

    private static Actor player(Player p) {
        return Actor.player(p.getUniqueId(), p.getName());
    }
}
