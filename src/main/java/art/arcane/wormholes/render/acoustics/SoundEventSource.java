package art.arcane.wormholes.render.acoustics;

import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.weather.LightningStrikeEvent;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.ViewServer;

/**
 * Destination-side listener that turns world events into {@link AcousticsBridge.SoundEvent}s for
 * local observers and forwards them to cross-server subscribers. Every handler bails immediately for
 * worlds no projecting portal looks into.
 */
public final class SoundEventSource implements Listener {
    private final Supplier<AcousticsBridge> bridge;

    public SoundEventSource(Supplier<AcousticsBridge> bridge) {
        this.bridge = bridge;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        emit(block.getWorld(), block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D,
            soundKey(block, true), 1.0F, 0.8F, AcousticsProfile.SoundClass.WORLD);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        emit(block.getWorld(), block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D,
            soundKey(block, false), 1.0F, 0.8F, AcousticsProfile.SoundClass.WORLD);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Location location = event.getLocation();
        emit(location.getWorld(), location.getX(), location.getY(), location.getZ(),
            "minecraft:entity.generic.explode", 4.0F, 0.8F, AcousticsProfile.SoundClass.WORLD);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity) || event.getFinalDamage() <= 0.0D) {
            return;
        }
        Location location = entity.getLocation();
        emit(location.getWorld(), location.getX(), location.getY(), location.getZ(),
            "minecraft:entity.generic.hurt", 1.0F, 1.0F, AcousticsProfile.SoundClass.ENTITY);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Location location = event.getEntity().getLocation();
        emit(location.getWorld(), location.getX(), location.getY(), location.getZ(),
            "minecraft:entity.arrow.hit", 1.0F, 1.2F, AcousticsProfile.SoundClass.ENTITY);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        String key = interactKey(block.getType(), block.getBlockData());
        if (key == null) {
            return;
        }
        emit(block.getWorld(), block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D, key, 1.0F, 1.0F,
            AcousticsProfile.SoundClass.WORLD);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLightning(LightningStrikeEvent event) {
        Location location = event.getLightning().getLocation();
        emit(location.getWorld(), location.getX(), location.getY(), location.getZ(),
            "minecraft:entity.lightning_bolt.thunder", 10.0F, 0.8F, AcousticsProfile.SoundClass.WORLD);
    }

    static String soundKey(Block block, boolean broken) {
        try {
            SoundGroup group = block.getBlockData().getSoundGroup();
            return (broken ? group.getBreakSound() : group.getPlaceSound()).getKey().toString();
        } catch (RuntimeException unavailable) {
            return broken ? "minecraft:block.stone.break" : "minecraft:block.stone.place";
        }
    }

    static String interactKey(Material material, BlockData data) {
        String name = material.name();
        if (data instanceof Openable) {
            if (name.contains("TRAPDOOR")) {
                return name.startsWith("IRON") ? "minecraft:block.iron_trapdoor.open" : "minecraft:block.wooden_trapdoor.open";
            }
            if (name.contains("DOOR")) {
                return name.startsWith("IRON") ? "minecraft:block.iron_door.toggle" : "minecraft:block.wooden_door.toggle";
            }
            if (name.contains("FENCE_GATE")) {
                return "minecraft:block.fence_gate.open";
            }
        }
        return switch (name) {
            case "CHEST", "TRAPPED_CHEST" -> "minecraft:block.chest.open";
            case "ENDER_CHEST" -> "minecraft:block.ender_chest.open";
            case "BARREL" -> "minecraft:block.barrel.open";
            default -> name.endsWith("SHULKER_BOX") ? "minecraft:block.shulker_box.open" : null;
        };
    }

    private void emit(World world, double x, double y, double z, String soundKey, float volume, float pitch,
                      AcousticsProfile.SoundClass soundClass) {
        if (world == null) {
            return;
        }
        ViewServer viewServer = Wormholes.viewServer;
        if (viewServer != null) {
            viewServer.forwardSound(world, x, y, z, soundKey, volume, pitch, soundClass);
        }
        AcousticsBridge active = bridge.get();
        if (active == null || !active.hasDestinationWorld(world.getUID())) {
            return;
        }
        active.onEvent(new AcousticsBridge.SoundEvent(world.getUID(), x, y, z, soundKey, volume, pitch, soundClass),
            System.currentTimeMillis());
    }
}
