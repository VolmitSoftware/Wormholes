package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftDyedItems;
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
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
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

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

/**
 * A portal view must be shaded like the world around it. The same rig, a white wall with a sunlit face and the shadow
 * of an overhang, a pool of water in front of it and the sky above, stands at two sites: one looked at directly, and the
 * destination of a portal whose aperture fills the whole view from a bare stand twenty blocks east of it, so the main
 * shadow map covers both the rig and its image. The frame through the portal and
 * the direct frame are compared region by region; the lit and shadowed wall must match, the water and sky are
 * reported. Run alone with gametest.py --metal to check Metal Shaders, or on any renderer for the same contract.
 */
public final class MetalShadedViewClientGameTest implements FabricClientGameTest {
    public static final String SHADING_LINE = "WORMHOLES_STREAMED_SHADING";
    public static final String REGION_LINE = "WORMHOLES_SHADED_REGION";
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos REFERENCE = new BlockPos(-300, 110, 400);
    private static final BlockPos SOURCE = new BlockPos(-300, 110, 300);
    private static final BlockPos DESTINATION = new BlockPos(-320, 110, 300);
    private static final BlockPos ROTATED_SOURCE = new BlockPos(-300, 110, 700);
    private static final BlockPos ROTATED_DESTINATION = new BlockPos(-340, 110, 700);
    private static final float NORTH_YAW = 180.0F;
    private static final BlockPos END_SOURCE = new BlockPos(-300, 110, 500);
    private static final BlockPos END_DESTINATION = new BlockPos(100, 80, 0);
    private static final BlockPos END_REFERENCE = new BlockPos(100, 80, 100);
    private static final BlockPos END_SAME_SOURCE = new BlockPos(100, 80, 200);
    private static final BlockPos END_SAME_DESTINATION = new BlockPos(60, 80, 200);
    private static final int END_TOLERANCE = 64;
    private static final int END_MINIMUM_LUMA = 20;
    private static final int APERTURE_WIDTH = 9;
    private static final int APERTURE_HEIGHT = 7;
    private static final int WALL_DEPTH = 12;
    private static final int OVERHANG_DEPTH = 6;
    private static final double EYE_HEIGHT = 1.62D;
    private static final double EYE_ABOVE_SILL = 2.62D;
    private static final double STAND_DISTANCE = 3.0D;
    private static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 80;
    private static final int PROBE_RADIUS = 6;
    private static final int WALL_TOLERANCE = 14;
    private static final int SHADOW_CONTRAST = 12;
    private static final int CLOUD_BLOCK = 32;
    private static final int CLOUD_BAND_ROWS = 400;
    private static final int CLOUD_BAND_TOP = 96;
    private static final double CLOUD_EDGE_DEVIATION = 15.0D;
    private static final double CLOUD_DETAIL_SHARE = 0.5D;
    private static final float SKY_PITCH = -20.0F;
    private static final int SKY_SETTLE_TICKS = 40;
    private static final int CLOUD_MARGIN = 220;
    private static final Region[] REGIONS = {
        new Region("lit", -WALL_DEPTH + 1, 1.0D, 0.5D, true),
        new Region("shadow", -WALL_DEPTH + 1, 4.8D, 0.5D, true),
        new Region("water", -3.5D, -0.125D, 0.5D, false),
        new Region("sky", -WALL_DEPTH + 1, 10.0D, 0.5D, false)};

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
                minecraft.options.cloudStatus().set(CloudStatus.FANCY);
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            List<String> failures = new ArrayList<>();
            stand(client, server, connection, Level.OVERWORLD, REFERENCE, false);
            FramebufferProbe direct = capture(client, output(client, "direct"));
            int[][] directMeans = means(client, direct, REFERENCE);
            FramebufferProbe directSky = skyward(client, "direct-sky");
            stand(client, server, connection, Level.OVERWORLD, SOURCE, true);
            shadingLine(client);
            FramebufferProbe portal = capture(client, output(client, "portal"));
            int[][] portalMeans = means(client, portal, SOURCE);
            FramebufferProbe portalSky = skyward(client, "portal-sky");
            compare("", directMeans, portalMeans, failures);
            clouds("clouds", directSky, portalSky, failures);
            standAt(client, server, connection, Level.OVERWORLD, rotatedEye(), NORTH_YAW, SKY_PITCH, false);
            FramebufferProbe directRotated = capture(client, output(client, "direct-rotated"));
            stand(client, server, connection, Level.OVERWORLD, ROTATED_SOURCE, true);
            FramebufferProbe rotated = skyward(client, "rotated-portal");
            clouds("clouds-rotated", directRotated, rotated, failures);
            stand(client, server, connection, Level.OVERWORLD, END_SOURCE, true);
            FramebufferProbe endPortal = capture(client, output(client, "end-portal"));
            int[][] endPortalMeans = means(client, endPortal, END_SOURCE);
            stand(client, server, connection, Level.END, END_REFERENCE, false);
            FramebufferProbe endDirect = capture(client, output(client, "end-direct"));
            int[][] endDirectMeans = means(client, endDirect, END_REFERENCE);
            compareEnd("end", endDirectMeans, endPortalMeans, failures);
            stand(client, server, connection, Level.END, END_DESTINATION, true);
            FramebufferProbe fromEnd = capture(client, output(client, "overworld-from-end-portal"));
            int[][] fromEndMeans = means(client, fromEnd, END_DESTINATION);
            compareEnd("from-end", directMeans, fromEndMeans, failures);
            stand(client, server, connection, Level.END, END_SAME_SOURCE, true);
            FramebufferProbe endSame = capture(client, output(client, "end-same-portal"));
            int[][] endSameMeans = means(client, endSame, END_SAME_SOURCE);
            compare("end-same", endDirectMeans, endSameMeans, failures);
            server.removePortals(portals);
            SeamlessScenario.assertTrue(failures.isEmpty(), String.join("; ", failures));
        }
    }

    private static List<UUID> build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        CommandSourceStack source = server.createCommandSourceStack();
        server.getCommands().performPrefixedCommand(source, "time set 2000");
        server.getCommands().performPrefixedCommand(source, "gamerule doDaylightCycle false");
        server.getCommands().performPrefixedCommand(source, "gamerule doWeatherCycle false");
        server.getCommands().performPrefixedCommand(source, "weather clear");
        ServerLevel end = server.getLevel(Level.END);
        ground(level, SOURCE);
        ground(level, ROTATED_SOURCE);
        ground(level, ROTATED_DESTINATION);
        rig(level, REFERENCE);
        rig(level, DESTINATION);
        rig(level, END_SOURCE);
        rig(end, END_DESTINATION);
        rig(end, END_REFERENCE);
        ground(end, END_SAME_SOURCE);
        rig(end, END_SAME_DESTINATION);
        MinecraftPortal sourcePortal = runtime.portals().create(actor.getUUID(), level, cells(SOURCE), PortalType.WORMHOLE, new Vec3(-1, 0, 0));
        MinecraftPortal destinationPortal = runtime.portals().create(actor.getUUID(), level, cells(DESTINATION), PortalType.WORMHOLE, new Vec3(-1, 0, 0));
        MinecraftPortal rotatedSource = runtime.portals().create(actor.getUUID(), level, cells(ROTATED_SOURCE), PortalType.WORMHOLE, new Vec3(-1, 0, 0));
        MinecraftPortal rotatedDestination = runtime.portals().create(actor.getUUID(), level, northCells(ROTATED_DESTINATION), PortalType.WORMHOLE, new Vec3(0, 0, -1));
        SeamlessScenario.assertTrue(rotatedSource != null && rotatedDestination != null, "rotated shaded view portal creation rejected");
        rotatedSource.setAmbientStyle(AmbientParticleStyle.OFF);
        rotatedDestination.setAmbientStyle(AmbientParticleStyle.OFF);
        SeamlessScenario.assertTrue(runtime.portals().link(actor, rotatedSource.getId(), rotatedDestination.getId()), "rotated shaded view link rejected");
        MinecraftPortal endSource = runtime.portals().create(actor.getUUID(), level, cells(END_SOURCE), PortalType.WORMHOLE, new Vec3(-1, 0, 0));
        MinecraftPortal endDestination = runtime.portals().create(actor.getUUID(), end, cells(END_DESTINATION), PortalType.WORMHOLE, new Vec3(-1, 0, 0));
        MinecraftPortal endSameSource = runtime.portals().create(actor.getUUID(), end, cells(END_SAME_SOURCE), PortalType.WORMHOLE, new Vec3(-1, 0, 0));
        MinecraftPortal endSameDestination = runtime.portals().create(actor.getUUID(), end, cells(END_SAME_DESTINATION), PortalType.WORMHOLE, new Vec3(-1, 0, 0));
        SeamlessScenario.assertTrue(sourcePortal != null && destinationPortal != null && endSource != null && endDestination != null
            && endSameSource != null && endSameDestination != null, "shaded view portal creation rejected");
        for (MinecraftPortal portal : List.of(sourcePortal, destinationPortal, endSource, endDestination, endSameSource, endSameDestination)) {
            portal.setAmbientStyle(AmbientParticleStyle.OFF);
        }
        SeamlessScenario.assertTrue(runtime.portals().link(actor, endSameSource.getId(), endSameDestination.getId()), "shaded view End-to-End link rejected");
        SeamlessScenario.assertTrue(runtime.portals().link(actor, sourcePortal.getId(), destinationPortal.getId()), "shaded view source link rejected");
        SeamlessScenario.assertTrue(runtime.portals().link(actor, destinationPortal.getId(), sourcePortal.getId()), "shaded view return link rejected");
        SeamlessScenario.assertTrue(runtime.portals().link(actor, endSource.getId(), endDestination.getId()), "shaded view End link rejected");
        SeamlessScenario.assertTrue(runtime.portals().link(actor, endDestination.getId(), endSource.getId()), "shaded view End return link rejected");
        actor.setGameMode(GameType.CREATIVE);
        actor.getAbilities().mayfly = true;
        actor.getAbilities().flying = true;
        actor.onUpdateAbilities();
        return List.of(sourcePortal.getId(), destinationPortal.getId(), rotatedSource.getId(), rotatedDestination.getId(), endSource.getId(), endDestination.getId(),
            endSameSource.getId(), endSameDestination.getId());
    }

    /** The rig west of a site's sill: open ground, a white wall whose east face the morning sun lights under a six-block overhang, and a pool. */
    private static void rig(ServerLevel level, BlockPos site) {
        int centerZ = site.getZ() + APERTURE_WIDTH / 2;
        ground(level, site);
        fill(level, site.getX() - WALL_DEPTH, site.getY() - 1, centerZ - 8, site.getX() - WALL_DEPTH, site.getY() + 7, centerZ + 8, MinecraftDyedItems.concreteBlock(DyeColor.WHITE).defaultBlockState());
        fill(level, site.getX() - WALL_DEPTH + 1, site.getY() + 6, centerZ - 8, site.getX() - WALL_DEPTH + OVERHANG_DEPTH, site.getY() + 6, centerZ + 8, MinecraftDyedItems.concreteBlock(DyeColor.WHITE).defaultBlockState());
        fill(level, site.getX() - 5, site.getY() - 1, centerZ - 2, site.getX() - 2, site.getY() - 1, centerZ + 2, Blocks.WATER.defaultBlockState());
    }

    /** Open air over a stone floor around a site's sill, so a stand sees nothing but the rig, or the view through the portal. */
    private static void ground(ServerLevel level, BlockPos site) {
        int centerZ = site.getZ() + APERTURE_WIDTH / 2;
        fill(level, site.getX() - 16, site.getY() - 2, centerZ - 10, site.getX() + 6, site.getY() + 14, centerZ + 10, Blocks.AIR.defaultBlockState());
        fill(level, site.getX() - 16, site.getY() - 2, centerZ - 10, site.getX() + 6, site.getY() - 1, centerZ + 10, Blocks.STONE.defaultBlockState());
    }

    /** Writes through the level so the chunks load; the sites are far from the spawn chunks. */
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

    private static void stand(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, ResourceKey<Level> dimension, BlockPos site,
                              boolean portal) {
        standAt(client, server, connection, dimension, eye(site), 90.0F, 0.0F, portal);
    }

    private static void standAt(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, ResourceKey<Level> dimension, Vec3 eye,
                                float yaw, float pitch, boolean portal) {
        server.computeOnServer(minecraftServer -> {
            ServerLevel level = minecraftServer.getLevel(dimension);
            ServerPlayer player = connection.getServerPlayer();
            level.setBlockAndUpdate(BlockPos.containing(eye.x, eye.y - EYE_HEIGHT - 0.5D, eye.z), Blocks.BARRIER.defaultBlockState());
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            return player.teleportTo(level, eye.x, eye.y - EYE_HEIGHT, eye.z, Set.of(), yaw, pitch, false);
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.level != null && minecraft.level.dimension() == dimension
            && minecraft.player.position().distanceTo(new Vec3(eye.x, eye.y - EYE_HEIGHT, eye.z)) < 0.05D, TIMEOUT_TICKS);
        client.lookAt(yaw, pitch);
        client.waitForChunksRender();
        if (portal) {
            client.waitFor(minecraft -> PortalStencilRenderer.instance().drawnViews() + ClientPortalRenderer.instance().drawnViews() > 0, TIMEOUT_TICKS);
        }
        client.waitTicks(SETTLE_TICKS);
    }

    /** The same stand tilted up, so most of the frame is sky above the rig; the aperture still covers it from the source stand. */
    private static FramebufferProbe skyward(FabricSeamlessClient client, String name) {
        client.lookAt(90.0F, SKY_PITCH);
        client.waitTicks(SKY_SETTLE_TICKS);
        return capture(client, output(client, name));
    }

    private static Vec3 eye(BlockPos site) {
        return new Vec3(site.getX() + STAND_DISTANCE, site.getY() + EYE_ABOVE_SILL, site.getZ() + APERTURE_WIDTH / 2 + 0.5D);
    }

    /** Where the view through the rotated portal looks from: the source stand turned a quarter turn about the north-facing destination. */
    private static Vec3 rotatedEye() {
        return new Vec3(ROTATED_DESTINATION.getX() + APERTURE_WIDTH / 2 + 0.5D, ROTATED_DESTINATION.getY() + EYE_ABOVE_SILL, ROTATED_DESTINATION.getZ() + STAND_DISTANCE);
    }

    private static void shadingLine(FabricSeamlessClient client) {
        String backend = client.computeOnClient(minecraft -> ClientPortalRenderer.instance().streamedShading().name());
        boolean active = client.computeOnClient(minecraft -> ClientPortalRenderer.instance().streamedShadingActive());
        String backends = client.computeOnClient(minecraft -> PortalBackends.describe());
        int stencil = client.computeOnClient(minecraft -> PortalStencilRenderer.instance().drawnViews());
        int mesh = client.computeOnClient(minecraft -> ClientPortalRenderer.instance().drawnViews());
        LOGGER.info("{} backend={} active={} backends={} stencilViews={} meshViews={}", SHADING_LINE, backend, active, backends, stencil, mesh);
    }

    /** The mean colour of each region, sampled where the rig's point projects from this site's stand. */
    private static int[][] means(FabricSeamlessClient client, FramebufferProbe frame, BlockPos site) {
        int[][] means = new int[REGIONS.length][];
        for (int index = 0; index < REGIONS.length; index++) {
            Region region = REGIONS[index];
            Vec3 point = new Vec3(site.getX() + region.dx(), site.getY() + region.dy(), site.getZ() + APERTURE_WIDTH / 2 + region.dz());
            int[] pixel = client.computeOnClient(minecraft -> FramebufferProbe.screen(minecraft, point));
            SeamlessScenario.assertTrue(pixel != null, "region " + region.name() + " projects off screen from " + site);
            int color = frame.average(pixel[0], pixel[1], PROBE_RADIUS);
            means[index] = new int[] {(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF};
        }
        return means;
    }

    /** A view against the direct view of the same rig under the same sky: the renderer lights both, so the walls must match closely. */
    private static void compare(String name, int[][] direct, int[][] portal, List<String> failures) {
        for (int index = 0; index < REGIONS.length; index++) {
            Region region = REGIONS[index];
            int delta = 0;
            for (int channel = 0; channel < 3; channel++) {
                delta = Math.max(delta, Math.abs(direct[index][channel] - portal[index][channel]));
            }
            LOGGER.info("{} region={} direct={} portal={} delta={}", REGION_LINE, label(name, region.name()), hex(direct[index]), hex(portal[index]), delta);
            if (region.gated() && delta > WALL_TOLERANCE) {
                failures.add(label(name, region.name()) + " wall reads " + hex(portal[index]) + " through the portal against " + hex(direct[index]) + " directly");
            }
        }
        int directContrast = luma(direct[0]) - luma(direct[1]);
        int portalContrast = luma(portal[0]) - luma(portal[1]);
        LOGGER.info("{} region={} direct={} portal={}", REGION_LINE, label(name, "contrast"), directContrast, portalContrast);
        if (name.isEmpty() && directContrast < SHADOW_CONTRAST) {
            failures.add("the overhang casts no shadow on the directly seen wall: lit " + hex(direct[0]) + " shadow " + hex(direct[1]));
        }
    }

    /**
     * Cloud detail over the sky band of the tilted captures, inside the aperture from the source stand: the clouds drift
     * between the two captures, so blocks are not compared one to one; instead each frame's mean luma over the band and
     * the largest and 90th-percentile luma deviation of its blocks say whether the view carries clouds with edges, as the
     * direct view does, or a smooth stand-in. When the direct sky has cloud edges the view must keep at least half of them.
     */
    private static void clouds(String name, FramebufferProbe direct, FramebufferProbe portal, List<String> failures) {
        double[] directBand = band(direct);
        double[] portalBand = band(portal);
        LOGGER.info("{} region={} directLuma={} portalLuma={} directDeviationMax={} portalDeviationMax={} directDeviationP90={} portalDeviationP90={}",
            REGION_LINE, name, Math.round(directBand[0]), Math.round(portalBand[0]), String.format("%.1f", directBand[1]), String.format("%.1f", portalBand[1]),
            String.format("%.1f", directBand[2]), String.format("%.1f", portalBand[2]));
        if (directBand[1] >= CLOUD_EDGE_DEVIATION && portalBand[1] < directBand[1] * CLOUD_DETAIL_SHARE) {
            failures.add(name + " through the portal keep a largest block deviation of " + String.format("%.1f", portalBand[1])
                + " against " + String.format("%.1f", directBand[1]) + " directly");
        }
    }

    /** Mean luma, the largest and the 90th-percentile block luma deviation over the sky band. */
    private static double[] band(FramebufferProbe frame) {
        List<Double> deviations = new ArrayList<>();
        double luma = 0.0D;
        for (int y = CLOUD_BAND_TOP; y + CLOUD_BLOCK <= Math.min(CLOUD_BAND_ROWS, frame.height()); y += CLOUD_BLOCK / 2) {
            for (int x = CLOUD_MARGIN; x + CLOUD_BLOCK <= frame.width() - CLOUD_MARGIN; x += CLOUD_BLOCK / 2) {
                double[] block = statistics(frame, x, y);
                luma += block[3];
                deviations.add(block[4]);
            }
        }
        deviations.sort(null);
        int count = deviations.size();
        return new double[] {luma / count, deviations.get(count - 1), deviations.get((int) Math.floor((count - 1) * 0.9D))};
    }

    /** Mean red, green, blue, mean luma and the luma's standard deviation over a cloud block whose top-left corner is given. */
    private static double[] statistics(FramebufferProbe frame, int x0, int y0) {
        double red = 0.0D;
        double green = 0.0D;
        double blue = 0.0D;
        double luma = 0.0D;
        double squares = 0.0D;
        int count = CLOUD_BLOCK * CLOUD_BLOCK;
        for (int y = y0; y < y0 + CLOUD_BLOCK; y++) {
            for (int x = x0; x < x0 + CLOUD_BLOCK; x++) {
                int argb = frame.read(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                double l = (r * 299 + g * 587 + b * 114) / 1000.0D;
                red += r;
                green += g;
                blue += b;
                luma += l;
                squares += l * l;
            }
        }
        double mean = luma / count;
        return new double[] {red / count, green / count, blue / count, mean, Math.sqrt(Math.max(0.0D, squares / count - mean * mean))};
    }

    private static String hex(double[] statistics) {
        return hex(new int[] {(int) Math.round(statistics[0]), (int) Math.round(statistics[1]), (int) Math.round(statistics[2])});
    }

    /**
     * A view into or out of the End against the direct view of the same rig under the camera's own sky. A view into the
     * End takes the End's light and sky palette under an estimated exposure (the renderer meters the real one per frame),
     * a view out of the End keeps the game's light, converted; both must still read as the rig, in colour and brightness.
     */
    private static void compareEnd(String name, int[][] direct, int[][] portal, List<String> failures) {
        for (int index = 0; index < REGIONS.length; index++) {
            Region region = REGIONS[index];
            int delta = 0;
            for (int channel = 0; channel < 3; channel++) {
                delta = Math.max(delta, Math.abs(direct[index][channel] - portal[index][channel]));
            }
            LOGGER.info("{} region={}-{} direct={} portal={} delta={}", REGION_LINE, name, region.name(), hex(direct[index]), hex(portal[index]), delta);
            if (luma(portal[index]) < END_MINIMUM_LUMA) {
                failures.add(name + " " + region.name() + " reads " + hex(portal[index]) + " through the portal, too dark to be the rig");
            }
            if (region.gated() && delta > END_TOLERANCE) {
                failures.add(name + " " + region.name() + " wall reads " + hex(portal[index]) + " through the portal against " + hex(direct[index]) + " directly");
            }
        }
    }

    private static String label(String name, String region) {
        return name.isEmpty() ? region : name + "-" + region;
    }

    private static int luma(int[] color) {
        return (color[0] * 299 + color[1] * 587 + color[2] * 114) / 1000;
    }

    private static String hex(int[] color) {
        return String.format("#%02x%02x%02x", color[0], color[1], color[2]);
    }

    /** A vertical aperture of the same size facing north, so the view through it from the west-facing source turns a quarter turn. */
    private static List<BlockPos> northCells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(APERTURE_WIDTH * APERTURE_HEIGHT);
        for (int x = 0; x < APERTURE_WIDTH; x++) {
            for (int y = 0; y < APERTURE_HEIGHT; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        return cells;
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

    private static Path output(FabricSeamlessClient client, String name) {
        return client.computeOnClient((Minecraft minecraft) -> minecraft.gameDirectory.toPath().resolve("screenshots/metal-shaded/" + name + ".png"));
    }

    private static FramebufferProbe capture(FabricSeamlessClient client, Path output) {
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        client.waitFor(minecraft -> captured.get() != null, TIMEOUT_TICKS);
        return captured.get();
    }

    /** A probe on the rig, offset from the site's sill corner: x west of it, y above it, z from the aperture's centre line. */
    private record Region(String name, double dx, double dy, double dz, boolean gated) {
    }
}
