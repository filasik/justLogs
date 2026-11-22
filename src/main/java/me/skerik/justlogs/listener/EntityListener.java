package me.skerik.justlogs.listener;

import me.skerik.justlogs.Context;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.EntityRecord;
import me.skerik.justlogs.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jspecify.annotations.Nullable;

/** Logs entity kills, player deaths, hanging/vehicle breaks, taming, shearing, leashing and bucket captures. */
public final class EntityListener implements Listener {

    private final Context ctx;

    public EntityListener(Context ctx) {
        this.ctx = ctx;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Location loc = entity.getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;

        if (entity instanceof Player victim) {
            if (!ctx.settings().logging().playerDeaths()) return;
            Actor killer = resolveKiller(entity);
            int cause = entity.getLastDamageCause() == null ? 0 : entity.getLastDamageCause().getCause().ordinal();
            String detail = entity.getLastDamageCause() == null ? null : entity.getLastDamageCause().getCause().name().toLowerCase();
            ctx.queue.offer(new EntityRecord(now(), killer, Action.PLAYER_DEATH, world,
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                    "player", Actor.player(victim.getUniqueId(), victim.getName()), cause, null, detail));
            return;
        }

        if (!ctx.settings().logging().entityKills()) return;
        Player killer = entity.getKiller();
        if (killer == null) return;
        ctx.queue.offer(new EntityRecord(now(), Actor.player(killer.getUniqueId(), killer.getName()), Action.KILL,
                world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.entity(entity.getType()), null, 0, customName(entity), null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (!ctx.settings().logging().hanging()) return;
        Player player = event.getPlayer();
        if (player == null) return;
        Location loc = event.getEntity().getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        ctx.queue.offer(new EntityRecord(now(), Actor.player(player.getUniqueId(), player.getName()),
                Action.HANGING_PLACE, world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.entity(event.getEntity().getType()), null, 0, null, null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!ctx.settings().logging().hanging()) return;
        Location loc = event.getEntity().getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        Actor actor = event instanceof HangingBreakByEntityEvent by
                ? actorFromEntity(by.getRemover())
                : Actor.source("#" + event.getCause().name().toLowerCase());
        ctx.queue.offer(new EntityRecord(now(), actor, Action.HANGING_BREAK, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.entity(event.getEntity().getType()), null, 0, null, null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!ctx.settings().logging().vehicles()) return;
        Location loc = event.getVehicle().getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        Actor actor = event.getAttacker() != null ? actorFromEntity(event.getAttacker()) : Actor.source("#nature");
        ctx.queue.offer(new EntityRecord(now(), actor, Action.VEHICLE_BREAK, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.entity(event.getVehicle().getType()), null, 0, customName(event.getVehicle()), null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (!ctx.settings().logging().entityKills()) return;
        Location loc = event.getEntity().getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        Player p = event.getPlayer();
        ctx.queue.offer(new EntityRecord(now(), Actor.player(p.getUniqueId(), p.getName()), Action.SHEAR, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.entity(event.getEntity().getType()), null, 0, customName(event.getEntity()), null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!ctx.settings().logging().entityKills()) return;
        if (!(event.getOwner() instanceof Player p)) return;
        Location loc = event.getEntity().getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        ctx.queue.offer(new EntityRecord(now(), Actor.player(p.getUniqueId(), p.getName()), Action.TAME, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.entity(event.getEntity().getType()), null, 0, customName(event.getEntity()), null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (!ctx.settings().logging().entityKills()) return;
        Location loc = event.getEntity().getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        Player p = event.getPlayer();
        ctx.queue.offer(new EntityRecord(now(), Actor.player(p.getUniqueId(), p.getName()), Action.LEASH, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.entity(event.getEntity().getType()), null, 0, customName(event.getEntity()), null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (!ctx.settings().logging().entityKills()) return;
        Location loc = event.getEntity().getLocation();
        String world = loc.getWorld().getName();
        if (!ctx.settings().logging().worldLogged(world)) return;
        Player p = event.getPlayer();
        ctx.queue.offer(new EntityRecord(now(), Actor.player(p.getUniqueId(), p.getName()), Action.BUCKET_ENTITY, world,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                Keys.entity(event.getEntity().getType()), null, 0, customName(event.getEntity()), null));
    }

    private static Actor resolveKiller(LivingEntity victim) {
        if (victim.getKiller() != null) {
            Player p = victim.getKiller();
            return Actor.player(p.getUniqueId(), p.getName());
        }
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent by) return actorFromEntity(by.getDamager());
        if (last != null) return Actor.source("#" + last.getCause().name().toLowerCase());
        return Actor.source("#nature");
    }

    private static Actor actorFromEntity(@Nullable Entity entity) {
        if (entity instanceof Player p) return Actor.player(p.getUniqueId(), p.getName());
        if (entity instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return Actor.player(p.getUniqueId(), p.getName());
        }
        if (entity != null) return Actor.source("#" + Keys.entity(entity.getType()));
        return Actor.source("#nature");
    }

    private static @Nullable String customName(Entity entity) {
        Component c = entity.customName();
        if (c == null) return null;
        String text = PlainTextComponentSerializer.plainText().serialize(c);
        return text.isBlank() ? null : (text.length() > 96 ? text.substring(0, 96) : text);
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }
}
