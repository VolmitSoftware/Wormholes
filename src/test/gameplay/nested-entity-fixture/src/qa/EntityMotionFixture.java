package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class EntityMotionFixture implements Listener {
    private final JavaPlugin plugin;
    private LocalPortal source;
    private LocalPortal destination;
    private Item moving;
    private String teleport = "pending";
    private Vector departureVelocity;
    private Vector arrivalVelocity;
    private double positionError = Double.NaN;
    private int ticks;

    EntityMotionFixture(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void execute(Player player, String[] args) {
        switch (args[1]) {
            case "setup" -> setup(player);
            case "launch" -> launch(player, args.length > 2 && args[2].equals("slow"));
            case "sample" -> player.sendMessage("MOTION state=" + teleport + " positionError=" + positionError
                + " departureVelocity=" + departureVelocity + " arrivalVelocity=" + arrivalVelocity
                + " ticks=" + ticks + " open=" + source.isOpen() + " linked=" + source.hasTunnel()
                + " position=" + (moving == null ? "none" : moving.getLocation().toVector()));
            default -> throw new IllegalArgumentException("Unknown motion command");
        }
    }

    private void setup(Player player) {
        if (plugin.getServer().getName().contains("Folia")) {
            throw new IllegalStateException("Motion fixture requires Paper's single region");
        }
        World world = player.getWorld();
        source = portal(player, 320, Direction.S, "source");
        destination = portal(player, 416, Direction.E, "destination");
        if (!source.setDestination(destination)) {
            throw new IllegalStateException("Motion fixture could not link portals");
        }
        source.save();
        destination.save();
        player.teleportAsync(new Location(world, 328.5, 191, 14.5, 180, 0))
            .thenAccept(done -> player.getScheduler().runDelayed(plugin,
                task -> player.sendMessage("MOTION ready=" + done), null, 40));
    }

    private LocalPortal portal(Player player, int offset, Direction normal, String name) {
        World world = player.getWorld();
        for (int chunkX = (offset - 16) >> 4; chunkX <= (offset + 32) >> 4; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 190; y <= 199; y++) {
                    world.getBlockAt(offset + x, y, z).setType(y == 190 ? Material.STONE : Material.AIR, false);
                }
            }
        }
        Set<Block> aperture = new HashSet<>();
        for (int lateral = 5; lateral <= 11; lateral++) {
            for (int y = 190; y <= 197; y++) {
                Block block = world.getBlockAt(offset + (normal == Direction.S ? lateral : 8), y,
                    normal == Direction.S ? 8 : lateral);
                if (lateral == 5 || lateral == 11 || y == 190 || y == 197) {
                    block.setType(Material.OBSIDIAN, false);
                } else {
                    aperture.add(block);
                }
            }
        }
        UUID id = UUID.nameUUIDFromBytes(("entity-motion-fixture:" + name).getBytes(StandardCharsets.UTF_8));
        LocalPortal portal = (LocalPortal) Wormholes.portalManager.getLocalPortal(id);
        if (portal == null) {
            PortalStructure structure = new PortalStructure();
            structure.setBlocks(aperture);
            portal = new LocalPortal(id, PortalType.PORTAL, structure);
            portal.setFrame(PortalFrame.canonical(normal));
            portal.setOwner(player.getUniqueId());
            portal.setProjectionMode(ProjectionMode.OFF);
            Wormholes.portalManager.addLocalPortal(portal);
        }
        portal.open();
        return portal;
    }

    private void launch(Player player, boolean slow) {
        if (moving != null) {
            moving.remove();
        }
        teleport = "pending";
        positionError = Double.NaN;
        arrivalVelocity = null;
        departureVelocity = null;
        ticks = 0;
        moving = player.getWorld().dropItem(new Location(player.getWorld(), 328.75, 193.25, slow ? 8.545 : 10.25), new ItemStack(Material.GOLD_INGOT));
        moving.setGravity(false);
        moving.setPickupDelay(Integer.MAX_VALUE);
        moving.setUnlimitedLifetime(true);
        moving.setVelocity(slow ? new Vector(0.0008, 0.0003, -0.008) : new Vector(0.08, 0.025, -0.6));
        moving.getScheduler().runAtFixedRate(plugin, task -> {
            ticks++;
            if (ticks >= 100 || !moving.isValid()) {
                task.cancel();
                return;
            }
            if (moving.getLocation().getX() > 400 && departureVelocity != null) {
                arrivalVelocity = moving.getVelocity().clone();
                teleport = "arrived";
                plugin.getLogger().info("Motion arrival positionError=" + positionError
                    + " departureVelocity=" + departureVelocity + " arrivalVelocity=" + arrivalVelocity);
                task.cancel();
            }
        }, null, 1, 1);
        player.sendMessage("MOTION launched=" + moving.getEntityId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        if (moving == null || !event.getEntity().getUniqueId().equals(moving.getUniqueId()) || event.getTo() == null) {
            return;
        }
        Vector expected = source.getFrame().transformPoint(event.getFrom().toVector(), source.getStructure().getCenter().toVector(),
            destination.getStructure().getCenter().toVector(), destination.getFrame());
        positionError = event.getTo().toVector().distance(expected);
        departureVelocity = source.getFrame().transformVector(moving.getVelocity(), destination.getFrame());
        teleport = "teleported";
        plugin.getLogger().info("Motion teleport from=" + event.getFrom().toVector() + " to=" + event.getTo().toVector()
            + " expected=" + expected + " positionError=" + positionError + " expectedVelocity=" + departureVelocity);
    }
}
