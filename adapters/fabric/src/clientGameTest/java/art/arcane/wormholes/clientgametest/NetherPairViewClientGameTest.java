package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalBackends;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.portalKey;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.section;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.sections;

/**
 * An overworld-to-Nether portal pair as a player sets one up: how far the view through it reaches, and whether a second
 * overworld portal onto the same Nether portal still shows when it is seen through the first (a nested view whose
 * destination is another dimension). Behind the Nether portal a row of sea lantern pillars stands every 16 blocks; the
 * view through the overworld portal is captured and the pillars counted along the horizon, once with the portal's
 * default network view depth and once with it raised (the client's render distance set beyond both, since the view is
 * also capped at the client's and the server's view distance), so the run log states what bounds the view on this renderer
 * (`[nether-view]` lines). Then, from the Nether, the view back into the overworld must carry the second portal's view
 * within it: the sea lanterns of the Nether corridor must show somewhere in the frame, which only that nested view can
 * show (a nested portal that fails its frame is dropped and shows nothing).
 */
public final class NetherPairViewClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String LINE = "[nether-view]";
    private static final BlockPos OVERWORLD_PORTAL = new BlockPos(-20, 78, 146);
    private static final BlockPos SECOND_PORTAL = new BlockPos(-50, 78, 154);
    private static final BlockPos NETHER_PORTAL = new BlockPos(14, 61, 18);
    private static final int APERTURE_WIDTH = 9;
    private static final int APERTURE_HEIGHT = 7;
    private static final int PILLARS = 12;
    private static final int PILLAR_SPACING = 16;
    private static final int PILLAR_HEIGHT = 8;
    private static final int CORRIDOR_HALF_WIDTH = 12;
    private static final int RAISED_DEPTH = 128;
    private static final int RENDER_DISTANCE = 12;
    private static final double STAND_DISTANCE = 3.0D;
    private static final double EYE_ABOVE_SILL = 2.62D;
    private static final double EYE_HEIGHT = 1.62D;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int TERRAIN_TIMEOUT_TICKS = 3600;
    private static final int SETTLE_TICKS = 120;
    private static final int RESETTLE_TICKS = 200;
    private static final double LANTERN_BLUE_OVER_RED = 1.15D;
    private static final double LANTERN_LUMA = 50.0D;
    private static final int LANTERN_GAP = 8;
    private static final int NESTED_LANTERN_PIXELS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricConnection connection = FabricConnection.of(singleplayer);
            FabricSeamlessClient client = new FabricSeamlessClient(context, connection);
            FabricSeamlessServer server = new FabricSeamlessServer(singleplayer.getServer(), connection);
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            List<UUID> portals = server.computeOnServer(minecraftServer -> build(minecraftServer, connection.getServerPlayer()));
            client.runOnClient(minecraft -> {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.renderDistance().set(RENDER_DISTANCE);
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            List<String> failures = new ArrayList<>();
            stand(client, server, connection, Level.OVERWORLD, OVERWORLD_PORTAL);
            client.waitTicks(SETTLE_TICKS);
            int defaultVisible = extent(client, server, "default", 64);
            server.computeOnServer(minecraftServer -> {
                runtime(minecraftServer).portals().get(portals.getFirst()).setNetworkViewDepth(RAISED_DEPTH);
                return null;
            });
            client.waitTicks(RESETTLE_TICKS);
            int raisedVisible = extent(client, server, "raised", RAISED_DEPTH);
            if (raisedVisible < defaultVisible) {
                failures.add("raising the network view depth to " + RAISED_DEPTH + " showed " + raisedVisible + " pillars against " + defaultVisible + " at the default");
            }
            stand(client, server, connection, Level.NETHER, NETHER_PORTAL);
            client.waitTicks(SETTLE_TICKS);
            waitForViews(client, 1);
            int lanterns = lanternPixels(capture(client, "through-nether-portal"));
            LOGGER.info("{} nested: lanternPixels={} path={}", LINE, lanterns, client.computeOnClient(minecraft -> PortalBackends.describe()));
            if (lanterns < NESTED_LANTERN_PIXELS) {
                failures.add("the second overworld portal's view of the Nether does not show through the Nether portal's view: " + lanterns + " lantern pixels");
            }
            server.removePortals(portals);
            SeamlessScenario.assertTrue(failures.isEmpty(), String.join("; ", failures));
        }
    }

    /** Counts the pillars along the horizon through the overworld portal and which of them the streamed view holds. */
    private static int extent(FabricSeamlessClient client, FabricSeamlessServer server, String phase, int depth) {
        int serverViewDistance = server.computeOnServer(minecraftServer -> minecraftServer.getPlayerList().getViewDistance());
        int clientViewDistance = client.computeOnClient(minecraft -> minecraft.options.getEffectiveRenderDistance());
        FramebufferProbe frame = capture(client, "through-overworld-portal-" + phase);
        int visible = pillarsAlongHorizon(frame);
        int key = client.computeOnClient(minecraft -> portalKey(OVERWORLD_PORTAL));
        int streamed = client.computeOnClient(minecraft -> sections(key));
        StringBuilder held = new StringBuilder();
        int farthestHeld = 0;
        for (int index = 1; index <= PILLARS; index++) {
            BlockPos pillar = pillar(index);
            boolean present = client.computeOnClient(minecraft -> section(key, pillar) != null);
            held.append(present ? '1' : '0');
            if (present) {
                farthestHeld = index * PILLAR_SPACING;
            }
        }
        LOGGER.info("{} phase={} depthSetting={} serverViewDistance={} clientViewDistance={} pillarsVisible={} farthestVisibleBlocks={} streamedSections={} pillarsHeld={} farthestHeldBlocks={} path={}",
            LINE, phase, depth, serverViewDistance, clientViewDistance, visible, visible * PILLAR_SPACING, streamed, held, farthestHeld,
            client.computeOnClient(minecraft -> PortalBackends.describe()));
        return visible;
    }

    /** Sea lantern runs (blue over the Nether's red haze) along the frame's middle row, a pillar's grout lines not splitting it. */
    private static int pillarsAlongHorizon(FramebufferProbe frame) {
        int runs = 0;
        int lastLantern = -LANTERN_GAP - 1;
        int y = frame.height() / 2;
        for (int x = 0; x < frame.width(); x++) {
            if (lantern(frame.read(x, y))) {
                if (x - lastLantern > LANTERN_GAP) {
                    runs++;
                }
                lastLantern = x;
            }
        }
        return runs;
    }

    /** Sea lantern pixels anywhere in the frame: through the nested portal they are the only ones a Nether view can show. */
    private static int lanternPixels(FramebufferProbe frame) {
        int count = 0;
        for (int y = 0; y < frame.height(); y += 2) {
            for (int x = 0; x < frame.width(); x += 2) {
                if (lantern(frame.read(x, y))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean lantern(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        double luma = 0.2126D * red + 0.7152D * green + 0.0722D * blue;
        return blue > red * LANTERN_BLUE_OVER_RED && luma > LANTERN_LUMA;
    }

    private static void waitForViews(FabricSeamlessClient client, int wanted) {
        int ticks = 0;
        int drawn = 0;
        while (ticks < TIMEOUT_TICKS) {
            drawn = client.computeOnClient(minecraft -> PortalStencilRenderer.instance().drawnViews() + ClientPortalRenderer.instance().drawnViews());
            if (drawn >= wanted) {
                return;
            }
            client.waitTicks(10);
            ticks += 10;
        }
    }

    private static List<UUID> build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime wormholes = runtime(server);
        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(Level.NETHER);
        ground(overworld, OVERWORLD_PORTAL, 40);
        ground(overworld, SECOND_PORTAL, 20);
        corridor(nether);
        MinecraftPortal first = wormholes.portals().create(actor.getUUID(), overworld, cells(OVERWORLD_PORTAL), PortalType.PORTAL, new Vec3(-1, 0, 0));
        MinecraftPortal second = wormholes.portals().create(actor.getUUID(), overworld, cells(SECOND_PORTAL), PortalType.PORTAL, new Vec3(-1, 0, 0));
        MinecraftPortal netherPortal = wormholes.portals().create(actor.getUUID(), nether, cells(NETHER_PORTAL), PortalType.PORTAL, new Vec3(-1, 0, 0));
        SeamlessScenario.assertTrue(first != null && second != null && netherPortal != null, "Nether pair portal creation rejected");
        for (MinecraftPortal portal : List.of(first, second, netherPortal)) {
            portal.setAmbientStyle(AmbientParticleStyle.OFF);
        }
        SeamlessScenario.assertTrue(wormholes.portals().link(actor, first.getId(), netherPortal.getId()), "Nether pair link rejected");
        SeamlessScenario.assertTrue(wormholes.portals().link(actor, netherPortal.getId(), first.getId()), "Nether pair return link rejected");
        SeamlessScenario.assertTrue(wormholes.portals().link(actor, second.getId(), netherPortal.getId()), "second overworld link rejected");
        actor.setGameMode(GameType.CREATIVE);
        actor.getAbilities().mayfly = true;
        actor.getAbilities().flying = true;
        actor.onUpdateAbilities();
        return List.of(first.getId(), second.getId(), netherPortal.getId());
    }

    /** Open air over a stone floor west of a portal site, as far as the pillars or the second portal need to be seen. */
    private static void ground(ServerLevel level, BlockPos site, int reach) {
        int centerZ = site.getZ() + APERTURE_WIDTH / 2;
        fill(level, site.getX() - reach, site.getY() - 2, centerZ - 16, site.getX() + 6, site.getY() + 14, centerZ + 16, Blocks.AIR.defaultBlockState());
        fill(level, site.getX() - reach, site.getY() - 2, centerZ - 16, site.getX() + 6, site.getY() - 1, centerZ + 16, Blocks.STONE.defaultBlockState());
    }

    /** A cleared corridor west of the Nether portal with a sea lantern pillar every 16 blocks. */
    private static void corridor(ServerLevel nether) {
        int centerZ = NETHER_PORTAL.getZ() + APERTURE_WIDTH / 2;
        int far = NETHER_PORTAL.getX() - PILLARS * PILLAR_SPACING - 8;
        fill(nether, far, NETHER_PORTAL.getY() - 2, centerZ - CORRIDOR_HALF_WIDTH, NETHER_PORTAL.getX() + 6, NETHER_PORTAL.getY() + 14, centerZ + CORRIDOR_HALF_WIDTH,
            Blocks.AIR.defaultBlockState());
        fill(nether, far, NETHER_PORTAL.getY() - 2, centerZ - CORRIDOR_HALF_WIDTH, NETHER_PORTAL.getX() + 6, NETHER_PORTAL.getY() - 1, centerZ + CORRIDOR_HALF_WIDTH,
            Blocks.NETHERRACK.defaultBlockState());
        for (int index = 1; index <= PILLARS; index++) {
            BlockPos base = pillar(index);
            fill(nether, base.getX() - 1, base.getY(), base.getZ() - 1, base.getX() + 1, base.getY() + PILLAR_HEIGHT - 1, base.getZ() + 1, Blocks.SEA_LANTERN.defaultBlockState());
        }
    }

    private static BlockPos pillar(int index) {
        return new BlockPos(NETHER_PORTAL.getX() - index * PILLAR_SPACING, NETHER_PORTAL.getY(), NETHER_PORTAL.getZ() + APERTURE_WIDTH / 2);
    }

    private static void fill(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = y1; y <= y2; y++) {
                    level.setBlock(cursor.set(x, y, z), state, 3);
                }
            }
        }
    }

    private static void stand(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, ResourceKey<Level> dimension, BlockPos site) {
        Vec3 eye = new Vec3(site.getX() + STAND_DISTANCE, site.getY() + EYE_ABOVE_SILL, site.getZ() + APERTURE_WIDTH / 2 + 0.5D);
        server.computeOnServer(minecraftServer -> {
            ServerLevel level = minecraftServer.getLevel(dimension);
            ServerPlayer player = connection.getServerPlayer();
            level.setBlockAndUpdate(BlockPos.containing(eye.x, eye.y - EYE_HEIGHT - 0.5D, eye.z), Blocks.BARRIER.defaultBlockState());
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            return player.teleportTo(level, eye.x, eye.y - EYE_HEIGHT, eye.z, Set.of(), 90.0F, 0.0F, false);
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.level != null && minecraft.level.dimension() == dimension
            && minecraft.player.position().distanceTo(new Vec3(eye.x, eye.y - EYE_HEIGHT, eye.z)) < 0.05D, TIMEOUT_TICKS);
        client.lookAt(90.0F, 0.0F);
        client.waitFor(MainRendererSections::terrainComplete, TERRAIN_TIMEOUT_TICKS);
        waitForViews(client, 1);
    }

    private static FramebufferProbe capture(FabricSeamlessClient client, String name) {
        Path output = client.computeOnClient((Minecraft minecraft) -> minecraft.gameDirectory.toPath().resolve("screenshots/nether-view/" + name + ".png"));
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        client.waitFor(minecraft -> captured.get() != null, TIMEOUT_TICKS);
        return captured.get();
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(APERTURE_WIDTH * APERTURE_HEIGHT);
        for (int z = 0; z < APERTURE_WIDTH; z++) {
            for (int y = 0; y < APERTURE_HEIGHT; y++) {
                cells.add(min.offset(0, y, z));
            }
        }
        return cells;
    }
}
