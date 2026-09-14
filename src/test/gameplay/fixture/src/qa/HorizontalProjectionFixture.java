package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class HorizontalProjectionFixture {
    private static final ThreadMXBean THREAD_METRICS = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);

    private HorizontalProjectionFixture() {
    }

    static void execute(JavaPlugin plugin, Player player, String[] args) {
        if (args.length < 2) {
            return;
        }
        switch (args[1]) {
            case "ticks" -> {
                if (args.length > 2 && args[2].equals("reset")) {
                    TickTimingFixture.reset(player);
                } else {
                    TickTimingFixture.sample(player);
                }
            }
            case "setup" -> {
                if (plugin.getServer().getName().contains("Folia")) {
                    throw new IllegalStateException("Horizontal fixture requires Paper's single region");
                }
                setup(plugin, player);
            }
            case "stage" -> {
                double x = args.length > 2 && args[2].equals("oblique") ? 11.5D : 6.5D;
                player.teleportAsync(new Location(player.getWorld(), x, 124, 8.5, 0, 90))
                    .thenAccept(done -> player.getScheduler().run(plugin, task -> {
                        player.setVelocity(new Vector());
                        player.sendMessage("HORIZONTAL staged=" + done);
                    }, null));
            }
            case "sample" -> sample(plugin, player);
            default -> throw new IllegalArgumentException("Unknown horizontal fixture command");
        }
    }

    private static void setup(JavaPlugin plugin, Player owner) {
        World world = owner.getWorld();
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        for (int x = -48; x <= 64; x++) {
            for (int z = -48; z <= 64; z++) {
                for (int y = 56; y <= 119; y++) {
                    setBlock(world.getBlockAt(x, y, z), Material.STONE);
                    setBlock(world.getBlockAt(x + 128, y, z), y >= 59 && y <= 61 ? Material.DEEPSLATE_BRICKS : Material.AIR);
                }
            }
        }
        for (int x = 0; x <= 16; x++) {
            for (int z = 0; z <= 16; z++) {
                for (int y = 120; y <= 128; y++) {
                    setBlock(world.getBlockAt(x, y, z), y == 123 ? Material.GLASS : Material.AIR);
                }
            }
        }
        for (int chunkX = -5; chunkX <= 13; chunkX++) {
            for (int chunkZ = -5; chunkZ <= 5; chunkZ++) {
                world.addPluginChunkTicket(chunkX, chunkZ, plugin);
                Wormholes.projectionChangeTracker.markChanged(world.getUID(), chunkX << 4, chunkZ << 4);
            }
        }
        ILocalPortal source = portal(world, owner.getUniqueId(), 0, "source");
        ILocalPortal target = portal(world, owner.getUniqueId(), 128, "target");
        if (!source.hasTunnel()) {
            if (!source.setDestination(target)) {
                throw new IllegalStateException("Fixture source failed to link");
            }
        }
        source.setProjectionMode(ProjectionMode.ON);
        target.setProjectionMode(ProjectionMode.OFF);
        source.save();
        target.save();
        owner.sendMessage("HORIZONTAL ready cells=37 direction=Down depth=" + source.getNetworkViewDepth()
            + " pad=" + source.getNetworkViewLateralPad() + " open=" + source.isOpen()
            + " linked=" + source.hasTunnel() + " source=" + source.getId() + " target=" + target.getId());
    }

    private static void setBlock(Block block, Material material) {
        if (block.getType() != material) {
            block.setType(material, false);
        }
    }

    private static ILocalPortal portal(World world, UUID owner, int offset, String name) {
        UUID id = UUID.nameUUIDFromBytes(("horizontal-fixture:" + name).getBytes(StandardCharsets.UTF_8));
        ILocalPortal existing = Wormholes.portalManager.getLocalPortal(id);
        if (existing != null) {
            return existing;
        }
        Set<Block> aperture = new HashSet<>();
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 12; z++) {
                if ((x == 4 || x == 8) && (z <= 5 || z >= 11)) {
                    continue;
                }
                aperture.add(world.getBlockAt(offset + x, 120, z));
            }
        }
        PortalStructure structure = new PortalStructure();
        structure.setBlocks(aperture);
        LocalPortal portal = new LocalPortal(id, PortalType.PORTAL, structure);
        portal.setName("Horizontal fixture " + name);
        portal.setFrame(PortalFrame.canonical(Direction.D));
        portal.setOwner(owner);
        portal.setNetworkViewDepth(64);
        portal.setNetworkViewLateralPad(48);
        portal.setRenderMode(ProjectionRenderMode.VENTICULAR);
        portal.setProjectionMode(ProjectionMode.OFF);
        Wormholes.portalManager.addLocalPortal(portal);
        portal.open();
        portal.save();
        return portal;
    }

    private static void sample(JavaPlugin plugin, Player player) {
        long now = System.currentTimeMillis();
        player.sendMessage("HORIZONTAL sample time=" + now + " tickMillis=" + plugin.getServer().getAverageTickTime()
            + " frameBudgetMicros=" + Settings.PROJECTION_MAX_FRAME_MICROS
            + " renderMillis=" + WormholesTelemetry.renderMsPerSecond(now)
            + " changes=" + WormholesTelemetry.blockChangesPerSecond(now)
            + " allocatedBytes=" + allocatedBytes()
            + " threadCpuNanos=" + THREAD_METRICS.getCurrentThreadCpuTime()
            + " failures=" + WormholesTelemetry.failures() + " active=" + WormholesTelemetry.activeProjections()
            + " position=" + player.getLocation().toVector());
        try {
            Field interestField = Wormholes.projectionManager.getClass().getDeclaredField("interestSet");
            interestField.setAccessible(true);
            Object interest = interestField.get(Wormholes.projectionManager);
            Field projectorsField = interest.getClass().getDeclaredField("projectors");
            projectorsField.setAccessible(true);
            Map<?, ?> portals = (Map<?, ?>) projectorsField.get(interest);
            for (Object value : portals.values()) {
                Map<?, ?> observers = (Map<?, ?>) value;
                for (Object candidate : observers.values()) {
                    PortalProjector projector = (PortalProjector) candidate;
                    player.sendMessage("HORIZONTAL projector observer=" + projector.getObserver().getName()
                        + " " + projector.getDiagnostics());
                }
            }
            player.sendMessage("HORIZONTAL sampleEnd");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Projection fixture diagnostics unavailable", error);
        }
    }

    private static long allocatedBytes() {
        if (!THREAD_METRICS.isThreadAllocatedMemorySupported()) {
            return -1L;
        }
        if (!THREAD_METRICS.isThreadAllocatedMemoryEnabled()) {
            THREAD_METRICS.setThreadAllocatedMemoryEnabled(true);
        }
        return THREAD_METRICS.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    static int viewerCount() {
        try {
            Field interestField = Wormholes.projectionManager.getClass().getDeclaredField("interestSet");
            interestField.setAccessible(true);
            Object interest = interestField.get(Wormholes.projectionManager);
            Field projectorsField = interest.getClass().getDeclaredField("projectors");
            projectorsField.setAccessible(true);
            Map<?, ?> portals = (Map<?, ?>) projectorsField.get(interest);
            Set<UUID> viewers = new HashSet<>();
            for (Object value : portals.values()) {
                Map<?, ?> observers = (Map<?, ?>) value;
                for (Object candidate : observers.values()) {
                    viewers.add(((PortalProjector) candidate).getObserver().getUniqueId());
                }
            }
            return viewers.size();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Projection fixture viewer count unavailable", error);
        }
    }

}
