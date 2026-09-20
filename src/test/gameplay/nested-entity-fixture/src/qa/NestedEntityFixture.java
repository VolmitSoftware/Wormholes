package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class NestedEntityFixture extends JavaPlugin {
    private final EntityMotionFixture motion = new EntityMotionFixture(this);
    private Item direct;
    private Item nested;
    private LocalPortal inner;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(motion, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length == 0) {
            return true;
        }
        switch (args[0]) {
            case "motion" -> motion.execute(player, args);
            case "setup" -> setup(player);
            case "stage" -> player.teleportAsync(new Location(player.getWorld(), 8.5, 191, 13.5, 180, 0))
                .thenAccept(done -> player.sendMessage("NESTED staged=" + done));
            case "hide" -> {
                player.hideEntity(this, nested);
                player.sendMessage("NESTED hidden=true");
            }
            case "show" -> {
                player.showEntity(this, nested);
                player.sendMessage("NESTED hidden=false");
            }
            case "mode" -> {
                inner.setProjectionMode(Boolean.parseBoolean(args[1]) ? ProjectionMode.ON : ProjectionMode.OFF);
                inner.save();
                player.sendMessage("NESTED mode=" + inner.getProjectionMode());
            }
            case "wall" -> {
                boolean enabled = Boolean.parseBoolean(args[1]);
                for (int x = 101; x <= 107; x++) {
                    for (int y = 190; y <= 197; y++) {
                        player.getWorld().getBlockAt(x, y, 4).setType(enabled ? Material.STONE : Material.AIR, false);
                    }
                }
                Wormholes.projectionChangeTracker.markChanged(player.getWorld().getUID(), 104, 4);
                player.sendMessage("NESTED wall=" + enabled);
            }
            case "sample" -> player.sendMessage("NESTED direct=" + direct.getEntityId() + " nested=" + nested.getEntityId()
                + " directPosition=" + direct.getLocation().toVector() + " nestedPosition=" + nested.getLocation().toVector());
            default -> player.sendMessage("NESTED unknown command");
        }
        return true;
    }

    private void setup(Player owner) {
        if (getServer().getName().contains("Folia")) {
            throw new IllegalStateException("Nested fixture requires Paper's single region");
        }
        World world = owner.getWorld();
        for (int offset : new int[] {0, 96, 192}) {
            for (int chunkX = (offset - 16) >> 4; chunkX <= (offset + 32) >> 4; chunkX++) {
                for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                    world.addPluginChunkTicket(chunkX, chunkZ, this);
                }
            }
            for (Item item : world.getNearbyEntitiesByType(Item.class, new Location(world, offset + 8.5, 191.25, 4.5), 8)) {
                item.remove();
            }
            for (int x = 0; x <= 16; x++) {
                for (int z = -12; z <= 16; z++) {
                    for (int y = 190; y <= 198; y++) {
                        world.getBlockAt(offset + x, y, z).setType(y == 190 ? Material.STONE : Material.AIR, false);
                    }
                }
            }
        }
        LocalPortal source = portal(owner, 0, 8, "source");
        LocalPortal target = portal(owner, 96, 8, "target");
        inner = portal(owner, 96, 2, "inner");
        LocalPortal far = portal(owner, 192, 8, "far");
        if (!source.setDestination(target) || !inner.setDestination(far)) {
            throw new IllegalStateException("Nested fixture portals could not link");
        }
        source.setProjectionMode(ProjectionMode.ON);
        inner.setProjectionMode(ProjectionMode.ON);
        for (LocalPortal portal : new LocalPortal[] {source, target, inner, far}) {
            portal.save();
        }
        direct = item(world, 104.5, 6.5, Material.DIAMOND);
        nested = item(world, 200.5, 4.5, Material.EMERALD);
        owner.sendMessage("NESTED ready=true direct=" + direct.getEntityId() + " nested=" + nested.getEntityId());
    }

    private LocalPortal portal(Player owner, int offset, int z, String name) {
        World world = owner.getWorld();
        Set<Block> blocks = new HashSet<>();
        for (int x = 5; x <= 11; x++) {
            for (int y = 190; y <= 197; y++) {
                Block block = world.getBlockAt(offset + x, y, z);
                if (x == 5 || x == 11 || y == 190 || y == 197) {
                    block.setType(Material.OBSIDIAN, false);
                } else {
                    blocks.add(block);
                }
            }
        }
        PortalStructure structure = new PortalStructure();
        structure.setBlocks(blocks);
        UUID id = UUID.nameUUIDFromBytes(("nested-entity-fixture:" + name).getBytes(StandardCharsets.UTF_8));
        LocalPortal portal = (LocalPortal) Wormholes.portalManager.getLocalPortal(id);
        if (portal == null) {
            portal = new LocalPortal(id, PortalType.PORTAL, structure);
            portal.setFrame(PortalFrame.canonical(Direction.S));
            portal.setOwner(owner.getUniqueId());
            portal.setName("Nested entity " + name);
            portal.setNetworkViewDepth(20);
            portal.setNetworkViewLateralPad(8);
            portal.setRenderMode(ProjectionRenderMode.VENTICULAR);
            portal.setProjectionMode(ProjectionMode.OFF);
            Wormholes.portalManager.addLocalPortal(portal);
        }
        portal.open();
        return portal;
    }

    private Item item(World world, double x, double z, Material material) {
        Item item = world.dropItem(new Location(world, x, 191.25, z), new ItemStack(material));
        item.setVelocity(new Vector());
        item.setGravity(false);
        item.setPickupDelay(Integer.MAX_VALUE);
        item.setUnlimitedLifetime(true);
        return item;
    }
}
