package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.fabric.WormholesFabric;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectedBlockStates;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientEntityIds;
import art.arcane.wormholes.modded.client.ClientMirrorBuilder;
import art.arcane.wormholes.modded.client.ClientNestedViews;
import art.arcane.wormholes.modded.client.ClientPlate;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ClientViewTick;
import art.arcane.wormholes.modded.client.ProjectionOverlay;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.DirectionMapping;
import art.arcane.wormholes.render.PortalCoordMap;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.ClientSpace;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ClientViewMirrorClientGameTest implements FabricClientGameTest {
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int STREAM_TIMEOUT_TICKS = 600;
    private static final int FOLLOW_TICKS = 20;
    private static final int FOLLOW_SWING_TICKS = 5;
    private static final double POSITION_EPSILON = 1.0E-6D;
    private static final double BOX_EPSILON = 1.0E-4D;
    private static final String UPSIDE_DOWN_NAME = "Dinnerbone";
    private static final BlockPos MIRROR_MIN = new BlockPos(40, 70, 20);
    private static final BlockPos GOLD_MARKER = new BlockPos(41, 71, 23);
    private static final BlockPos CHILD_MIN = new BlockPos(40, 70, 24);
    private static final BlockPos CHILD_DESTINATION_MIN = new BlockPos(40, 70, 300);
    private static final BlockPos CEILING_MIN = new BlockPos(80, 75, 20);
    private static final int CEILING_ROOM_HEIGHT = 5;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            runScenario(context, singleplayer.getConnection(), singleplayer.getServer(), "singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
             TestDedicatedServerConnection connection = server.connect()) {
            runScenario(context, connection, server, "dedicated");
        }
    }

    private void runScenario(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        boolean mirrorCaps = context.computeOnClient(client -> WormholesClient.instance().session().has(ClientViewCapability.CLIENT_MIRROR)
            && WormholesClient.instance().session().has(ClientViewCapability.CLIENT_RECURSION));
        assertTrue(mirrorCaps, "the session did not negotiate CLIENT_MIRROR and CLIENT_RECURSION");
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        UUID mirrorId = server.computeOnServer(minecraftServer -> buildMirror(minecraftServer, player));
        teleport(server, player, MIRROR_MIN.getX() + 1.5D, MIRROR_MIN.getY(), MIRROR_MIN.getZ() + 8.5D, 180.0F, 0.0F);
        connection.waitForChunksRender();
        context.waitFor(client -> mirrorKey() != 0 && mirrorCells() > 0, STREAM_TIMEOUT_TICKS);
        assertMirrorWithoutPlate(context);
        assertMirrorParity(context);
        context.takeScreenshot("clientview-mirror-" + label);
        assertSelfReflectionFollows(context);
        UUID[] child = server.computeOnServer(minecraftServer -> buildChild(minecraftServer, player));
        context.waitFor(client -> nestedCells() > 0, STREAM_TIMEOUT_TICKS);
        assertNestedContent(context, connection);
        context.takeScreenshot("clientview-mirror-nested-" + label);
        server.runOnServer(minecraftServer -> {
            WormholesModRuntime runtime = runtime();
            runtime.portals().remove(player, child[0]);
            runtime.portals().remove(player, child[1]);
            runtime.portals().remove(player, mirrorId);
        });
        context.waitFor(client -> WormholesClient.instance().tickState().overlay().size() == 0, STREAM_TIMEOUT_TICKS);
        int reflections = context.computeOnClient(client -> WormholesClient.instance().reflections().size());
        assertTrue(reflections == 0, "the self reflection outlived its mirror");
        runCeilingScenario(context, connection, server, player, label);
    }

    private void runCeilingScenario(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                                    String label) {
        UUID ceilingId = server.computeOnServer(minecraftServer -> buildCeilingMirror(minecraftServer, player));
        teleport(server, player, CEILING_MIN.getX() + 1.5D, CEILING_MIN.getY() - CEILING_ROOM_HEIGHT, CEILING_MIN.getZ() + 1.5D, 180.0F, -90.0F);
        connection.waitForChunksRender();
        context.waitFor(client -> client.player.onGround() && mirrorKey() != 0 && WormholesClient.instance().reflections().entity(mirrorKey()) != null,
            STREAM_TIMEOUT_TICKS);
        String standing = context.computeOnClient(client -> Math.abs(client.player.getY() - (CEILING_MIN.getY() - CEILING_ROOM_HEIGHT)) < POSITION_EPSILON
            ? null : "the player stands at " + client.player.position() + " instead of the ceiling room floor");
        assertTrue(standing == null, standing);
        String failure = context.computeOnClient(ClientViewMirrorClientGameTest::reflectionMismatch);
        assertTrue(failure == null, "ceiling mirror: " + failure);
        boolean flipped = context.computeOnClient(client -> {
            Entity entity = WormholesClient.instance().reflections().entity(mirrorKey());
            return entity.getCustomName() != null && UPSIDE_DOWN_NAME.equals(entity.getCustomName().getString());
        });
        assertTrue(flipped, "the ceiling reflection is not drawn upside down");
        connection.waitForChunksRender();
        context.takeScreenshot("clientview-mirror-ceiling-" + label);
        server.runOnServer(minecraftServer -> runtime().portals().remove(player, ceilingId));
        context.waitFor(client -> WormholesClient.instance().tickState().overlay().size() == 0, STREAM_TIMEOUT_TICKS);
        int reflections = context.computeOnClient(client -> WormholesClient.instance().reflections().size());
        assertTrue(reflections == 0, "the ceiling reflection outlived its mirror");
    }

    private static void assertMirrorWithoutPlate(ClientGameTestContext context) {
        String failure = context.computeOnClient(client -> {
            WormholesClient wormholes = WormholesClient.instance();
            int key = mirrorKey();
            ClientPortal portal = wormholes.session().portal(key);
            if (portal == null || portal.plate() != null) {
                return "the mirror portal holds a streamed plate";
            }
            if (wormholes.session().plates().plate(key) != null) {
                return "the plate store holds bytes for the mirror";
            }
            return null;
        });
        assertTrue(failure == null, failure);
    }

    private static void assertMirrorParity(ClientGameTestContext context) {
        List<String> mismatches = context.computeOnClient(client -> {
            List<String> failures = new ArrayList<>();
            WormholesClient wormholes = WormholesClient.instance();
            ClientViewTick tick = wormholes.tickState();
            int key = mirrorKey();
            ClientPortal portal = wormholes.session().portal(key);
            ClientPortalGeometry geometry = portal.geometry();
            DirectionMapping mapping = DirectionMapping.mirror(geometry.frame(), geometry.mirrorQuarterTurns(), new double[3]);
            GeometryVector origin = geometry.apertureArea().center();
            ProjectionOverlay overlay = tick.overlay();
            LongArrayList applied = new LongArrayList();
            portal.sweep().appliedKeys(applied);
            double[] source = new double[3];
            for (int index = 0; index < applied.size(); index++) {
                long cell = applied.getLong(index);
                ProjectionOverlay.Entry entry = overlay.get(cell);
                if (entry != null && entry.portalKey() != key) {
                    continue;
                }
                int x = ProjectionCellKey.unpackX(cell);
                int y = ProjectionCellKey.unpackY(cell);
                int z = ProjectionCellKey.unpackZ(cell);
                PortalCoordMap.mirrorDisplayToSourcePointInto(x + 0.5D, y + 0.5D, z + 0.5D, origin.getX(), origin.getY(), origin.getZ(),
                    geometry.frame(), geometry.mirrorQuarterTurns(), source);
                BlockPos sourcePos = new BlockPos((int) Math.floor(source[0]), (int) Math.floor(source[1]), (int) Math.floor(source[2]));
                ProjectionOverlay.Entry sourceEntry = overlay.get(ProjectionCellKey.pack(sourcePos.getX(), sourcePos.getY(), sourcePos.getZ()));
                BlockState shadow = sourceEntry != null && !sourceEntry.pending() ? sourceEntry.shadow() : client.level.getBlockState(sourcePos);
                BlockState expected = MinecraftProjectedBlockStates.transform(shadow, mapping);
                BlockState shown = client.level.getBlockState(new BlockPos(x, y, z));
                if (expected.isAir() ? !shown.isAir() && entry != null : shown != expected) {
                    failures.add(x + "," + y + "," + z + " shows " + shown + " for the reflection " + expected);
                }
            }
            return failures;
        });
        assertTrue(mismatches.isEmpty(), "mirror parity failed: " + mismatches);
    }

    private static void assertSelfReflectionFollows(ClientGameTestContext context) {
        for (int step = 0; step < FOLLOW_TICKS; step++) {
            boolean left = step % (FOLLOW_SWING_TICKS * 2) < FOLLOW_SWING_TICKS;
            context.getInput().holdKeyFor(options -> left ? options.keyLeft : options.keyRight, 1);
            String failure = context.computeOnClient(ClientViewMirrorClientGameTest::reflectionMismatch);
            assertTrue(failure == null, "step " + step + ": " + failure);
        }
    }

    private static String reflectionMismatch(Minecraft client) {
        WormholesClient wormholes = WormholesClient.instance();
        int key = mirrorKey();
        Entity entity = wormholes.reflections().entity(key);
        if (!(entity instanceof Mannequin)) {
            return "no self reflection for mirror " + key;
        }
        if (!ClientEntityIds.isReflection(entity.getId())) {
            return "the reflection uses entity id " + entity.getId();
        }
        ClientMirrorBuilder mirror = wormholes.tickState().mirror(key);
        ClientSpace space = mirror.space();
        double height = client.player.getBbHeight();
        double[] feet = new double[3];
        double[] head = new double[3];
        space.toDisplay(client.player.getX(), client.player.getY(), client.player.getZ(), feet);
        space.toDisplay(client.player.getX(), client.player.getY() + height, client.player.getZ(), head);
        AABB box = entity.getBoundingBox();
        double bottom = Math.min(feet[1], head[1]);
        double top = Math.max(feet[1], head[1]);
        if (Math.abs(box.minY - bottom) > BOX_EPSILON || Math.abs(box.maxY - top) > BOX_EPSILON) {
            return "the reflection spans y " + box.minY + ".." + box.maxY + " instead of the mirrored body " + bottom + ".." + top;
        }
        double[] expected = new double[3];
        space.entityToDisplay(client.player.getX(), client.player.getY(), client.player.getZ(), height, expected);
        if (Math.abs(entity.getX() - expected[0]) > POSITION_EPSILON || Math.abs(entity.getY() - expected[1]) > POSITION_EPSILON
            || Math.abs(entity.getZ() - expected[2]) > POSITION_EPSILON) {
            return "the reflection stands at " + entity.position() + " instead of " + expected[0] + "," + expected[1] + "," + expected[2];
        }
        space.entityToDisplay(client.player.xOld, client.player.yOld, client.player.zOld, height, expected);
        if (Math.abs(entity.xOld - expected[0]) > POSITION_EPSILON || Math.abs(entity.yOld - expected[1]) > POSITION_EPSILON
            || Math.abs(entity.zOld - expected[2]) > POSITION_EPSILON) {
            return "the reflection interpolates from " + entity.xOld + "," + entity.yOld + "," + entity.zOld + " instead of " + expected[0] + ","
                + expected[1] + "," + expected[2];
        }
        return null;
    }

    private static void assertNestedContent(ClientGameTestContext context, TestServerConnection connection) {
        List<String> mismatches = context.computeOnClient(client -> {
            List<String> failures = new ArrayList<>();
            WormholesClient wormholes = WormholesClient.instance();
            ProjectionOverlay overlay = wormholes.tickState().overlay();
            LongArrayList keys = overlay.keys();
            int nested = 0;
            int gold = 0;
            for (int index = 0; index < keys.size(); index++) {
                long key = keys.getLong(index);
                ProjectionOverlay.Entry entry = overlay.get(key);
                ClientPortal owner = wormholes.session().portal(entry.portalKey());
                if (owner == null || !owner.nested()) {
                    continue;
                }
                nested++;
                BlockPos position = new BlockPos(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key));
                BlockState shown = client.level.getBlockState(position);
                if (shown.getBlock() == Blocks.GOLD_BLOCK) {
                    gold++;
                } else if (!shown.isAir()) {
                    failures.add(position + " shows " + shown + " inside the nested portal");
                }
            }
            if (nested == 0 || gold == 0) {
                failures.add(nested + " overlay cells and " + gold + " gold cells belong to a nested portal; " + nestedDiagnostics(client));
            }
            return failures;
        });
        assertTrue(mismatches.isEmpty(), "nested content failed: " + mismatches);
    }

    private static String nestedDiagnostics(Minecraft client) {
        WormholesClient wormholes = WormholesClient.instance();
        ClientViewTick tick = wormholes.tickState();
        ClientNestedViews nested = tick.nestedViews();
        ProjectionOverlay overlay = tick.overlay();
        StringBuilder out = new StringBuilder("views=" + nested.size());
        int[] content = new int[3];
        for (ClientPortal portal : wormholes.session().portals().values()) {
            ClientPlate plate = portal.plate();
            out.append(" [key=").append(portal.portalKey()).append(" nested=").append(portal.nested()).append(" parent=")
                .append(portal.geometry().parentPortalKey()).append(" front=").append(portal.geometry().frontSide())
                .append(" plate=").append(plate == null ? "none" : plate.cells()).append(" viewCells=").append(nested.cells(portal.portalKey()))
                .append(" overlayOwned=").append(overlay.keysOf(portal.portalKey()).size())
                .append(" swept=").append(portal.sweep() == null ? 0 : portal.sweep().appliedCount());
            if (!portal.nested() || plate == null) {
                out.append(']');
                continue;
            }
            ClientPortal parent = wormholes.session().portal(portal.geometry().parentPortalKey());
            LongArrayList applied = new LongArrayList();
            if (parent != null && parent.sweep() != null) {
                parent.sweep().appliedKeys(applied);
            }
            int air = 0;
            int standIn = 0;
            int solid = 0;
            int outside = 0;
            int shadowAir = 0;
            int sampled = 0;
            for (int index = 0; index < applied.size(); index++) {
                long key = applied.getLong(index);
                int x = ProjectionCellKey.unpackX(key);
                int y = ProjectionCellKey.unpackY(key);
                int z = ProjectionCellKey.unpackZ(key);
                if (!nested.displays(portal.portalKey(), x, y, z)) {
                    continue;
                }
                nested.contentCell(portal.portalKey(), x, y, z, content);
                boolean inside = plate.contains(content[0], content[1], content[2]);
                int id = plate.paletteIdAt(content[0], content[1], content[2]);
                BlockState shown = client.level.getBlockState(new BlockPos(x, y, z));
                if (!inside) {
                    outside++;
                } else if (id == ClientViewProtocol.PALETTE_AIR) {
                    air++;
                } else if (id < ClientViewProtocol.RESERVED_PALETTE_IDS) {
                    standIn++;
                } else {
                    solid++;
                }
                if (shown.isAir()) {
                    shadowAir++;
                }
                if (sampled < 4) {
                    sampled++;
                    ProjectionOverlay.Entry entry = overlay.get(key);
                    out.append(" sample{display=").append(x).append(',').append(y).append(',').append(z).append(" content=").append(content[0])
                        .append(',').append(content[1]).append(',').append(content[2]).append(" inside=").append(inside).append(" id=").append(id)
                        .append(" shown=").append(shown).append(" entry=").append(entry == null ? "none" : entry.portalKey() + (entry.pending() ? "p" : ""))
                        .append('}');
                }
            }
            out.append(" displayed{air=").append(air).append(" standIn=").append(standIn).append(" solid=").append(solid).append(" outside=")
                .append(outside).append(" shownAir=").append(shadowAir).append("}]");
        }
        return out.toString();
    }

    private static int mirrorKey() {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null) {
            return 0;
        }
        IntArrayList keys = wormholes.tickState().mirrorKeys(new IntArrayList());
        return keys.isEmpty() ? 0 : keys.getInt(0);
    }

    private static int mirrorCells() {
        WormholesClient wormholes = WormholesClient.instance();
        ClientViewSession session = wormholes.session();
        ClientPortal portal = session.portal(mirrorKey());
        return portal == null || portal.sweep() == null ? 0 : portal.sweep().appliedCount();
    }

    private static int nestedCells() {
        WormholesClient wormholes = WormholesClient.instance();
        return wormholes == null ? 0 : wormholes.tickState().nestedViews().size();
    }

    private static UUID buildMirror(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, MIRROR_MIN.offset(-6, -1, -8), 15, 10, 8, Blocks.STONE.defaultBlockState());
        fill(level, MIRROR_MIN.offset(-6, 0, 1), 15, 8, 10, Blocks.AIR.defaultBlockState());
        fill(level, MIRROR_MIN.offset(-6, -1, 1), 15, 1, 10, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(GOLD_MARKER, Blocks.GOLD_BLOCK.defaultBlockState());
        MinecraftPortal mirror = runtime.portals().create(actor.getUUID(), level, cells(MIRROR_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        mirror.setMirrorMode(true);
        runtime.portals().save(mirror);
        return mirror.getId();
    }

    private static UUID buildCeilingMirror(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, CEILING_MIN.offset(-6, -CEILING_ROOM_HEIGHT - 1, -6), 15, 1, 15, Blocks.STONE.defaultBlockState());
        fill(level, CEILING_MIN.offset(-6, -CEILING_ROOM_HEIGHT, -6), 15, CEILING_ROOM_HEIGHT + 1, 15, Blocks.AIR.defaultBlockState());
        fill(level, CEILING_MIN.offset(-6, 1, -6), 15, 10, 15, Blocks.STONE.defaultBlockState());
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(CEILING_MIN.offset(x, 0, z));
            }
        }
        MinecraftPortal mirror = runtime.portals().create(actor.getUUID(), level, cells, PortalType.PORTAL, new Vec3(0, 1, 0));
        mirror.setMirrorMode(true);
        runtime.portals().save(mirror);
        return mirror.getId();
    }

    private static UUID[] buildChild(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, CHILD_DESTINATION_MIN.offset(-6, -2, -12), 15, 10, 12, Blocks.GOLD_BLOCK.defaultBlockState());
        fill(level, CHILD_DESTINATION_MIN.offset(-6, -2, 1), 15, 10, 12, Blocks.GOLD_BLOCK.defaultBlockState());
        MinecraftPortal child = runtime.portals().create(actor.getUUID(), level, cells(CHILD_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), level, cells(CHILD_DESTINATION_MIN), PortalType.PORTAL,
            new Vec3(0, 0, -1));
        assertTrue(runtime.portals().link(actor, child.getId(), destination.getId()), "child link rejected");
        return new UUID[] {child.getId(), destination.getId()};
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        return cells;
    }

    private static void fill(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, BlockState state) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    level.setBlockAndUpdate(min.offset(x, y, z), state);
                }
            }
        }
    }

    private static void teleport(TestServerContext server, ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        server.runOnServer(minecraftServer -> player.teleportTo(player.level(), x, y, z, Set.of(), yaw, pitch, false));
    }

    private static WormholesModRuntime runtime() {
        for (ModInitializer initializer : FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class)) {
            if (initializer instanceof WormholesFabric fabric) {
                try {
                    Field field = WormholesFabric.class.getDeclaredField("runtime");
                    field.setAccessible(true);
                    return (WormholesModRuntime) field.get(fabric);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Wormholes runtime is not reachable", failure);
                }
            }
        }
        throw new IllegalStateException("Wormholes main entrypoint is not loaded");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
