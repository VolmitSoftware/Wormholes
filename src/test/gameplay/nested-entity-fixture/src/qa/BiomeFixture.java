package qa;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class BiomeFixture {
    private final JavaPlugin plugin;

    BiomeFixture(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void clear() {
        for (String name : new String[]{"left-source", "left-destination", "right-source", "right-destination"}) {
            UUID id = UUID.nameUUIDFromBytes(("biome-projection-fixture:" + name).getBytes(StandardCharsets.UTF_8));
            LocalPortal portal = (LocalPortal) Wormholes.portalManager.getLocalPortal(id);
            if (portal != null) {
                portal.destroy();
            }
        }
    }

    void execute(Player player, String[] args) {
        if (args.length < 2 || !args[1].equals("setup")) {
            throw new IllegalArgumentException("Use /whnested biome setup");
        }
        if (plugin.getServer().getName().contains("Folia")) {
            throw new IllegalStateException("Biome fixture requires Paper's single region");
        }
        Biome biome = Objects.requireNonNull(Registry.BIOME.get(new NamespacedKey("iris", "biomes/qa_visual")),
            "Biome fixture datapack is missing");
        World world = player.getWorld();
        player.setGameMode(GameMode.CREATIVE);
        world.setTime(6000);
        world.setStorm(false);
        for (String rule : new String[]{"advance_time", "advance_weather", "spawn_mobs"}) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                "execute in " + world.getKey() + " run gamerule minecraft:" + rule + " false");
        }
        for (int offset : new int[]{0, 512}) {
            scenery(world, offset, biome);
        }
        pair(player, 4, 516, "left");
        pair(player, 10, 522, "right");
        player.sendMessage("[whqa] biome ready");
    }

    private void scenery(World world, int offset, Biome biome) {
        for (int x = (offset - 32) >> 4; x <= (offset + 48) >> 4; x++) {
            for (int z = -2; z <= 2; z++) {
                world.addPluginChunkTicket(x, z, plugin);
            }
        }
        for (int x = offset - 24; x <= offset + 40; x += 4) {
            for (int z = -24; z <= 24; z += 4) {
                for (int y = 176; y <= 224; y += 4) {
                    world.setBiome(x, y, z, biome);
                }
            }
        }
        for (int x = offset - 12; x <= offset + 28; x++) {
            for (int z = -20; z <= 20; z++) {
                for (int y = 199; y <= 205; y++) {
                    Material material = y == 199 ? Material.GRASS_BLOCK : Material.AIR;
                    if (y == 199 && Math.abs(z) >= 10 && Math.abs(z) <= 15
                        && (x - offset >= 0 && x - offset <= 3 || x - offset >= 12 && x - offset <= 15)) {
                        material = Material.WATER;
                    } else if (y >= 200 && y <= 203 && Math.abs(z) == 16 && (x - offset == 0 || x - offset == 15)) {
                        material = Material.OAK_LEAVES;
                    }
                    world.getBlockAt(x, y, z).setType(material, false);
                }
            }
        }
        Wormholes.projectionChangeTracker.markChanged(world.getUID(), offset + 8, 0);
    }

    private void pair(Player player, int sourceX, int destinationX, String name) {
        LocalPortal source = portal(player, sourceX, name + "-source");
        LocalPortal destination = portal(player, destinationX, name + "-destination");
        if (!source.setDestination(destination) || !destination.setDestination(source)) {
            throw new IllegalStateException("Biome fixture portals could not link");
        }
        source.save();
        destination.save();
    }

    private LocalPortal portal(Player owner, int centerX, String name) {
        UUID id = UUID.nameUUIDFromBytes(("biome-projection-fixture:" + name).getBytes(StandardCharsets.UTF_8));
        LocalPortal existing = (LocalPortal) Wormholes.portalManager.getLocalPortal(id);
        if (existing != null) {
            existing.destroy();
        }
        Set<Block> aperture = new HashSet<>();
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int y = 199; y <= 204; y++) {
                Block block = owner.getWorld().getBlockAt(x, y, 0);
                if (x == centerX - 2 || x == centerX + 2 || y == 199 || y == 204) {
                    block.setType(Material.OBSIDIAN, false);
                } else {
                    block.setType(Material.AIR, false);
                    aperture.add(block);
                }
            }
        }
        PortalStructure structure = new PortalStructure();
        structure.setBlocks(aperture);
        LocalPortal portal = new LocalPortal(id, PortalType.PORTAL, structure);
        portal.setFrame(Frame.canonical(Face.S));
        portal.setOwner(owner.getUniqueId());
        portal.setName("Biome fixture " + name);
        portal.setNetworkViewDepth(20);
        portal.setNetworkViewLateralPad(8);
        portal.setRenderMode(ProjectionRenderMode.PANOPTIC);
        portal.setProjectionMode(ProjectionMode.ON);
        Wormholes.portalManager.addLocalPortal(portal);
        portal.open();
        return portal;
    }
}
