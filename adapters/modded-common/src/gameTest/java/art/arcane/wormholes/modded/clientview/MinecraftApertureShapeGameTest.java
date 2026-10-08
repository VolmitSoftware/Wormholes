package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeRaster;
import art.arcane.optics.shape.Shapes;
import art.arcane.optics.stream.SessionPalette;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.RuntimeBaselineEnvironment;
import art.arcane.wormholes.modded.WormholesGameTests;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftApertureShapeGameTest {
    private static final ShapeDescriptor CIRCLE = ShapeDescriptor.parse("circle");
    private static final int EDGE = 7;
    private static final int SOURCE_X = 2;
    private static final int DESTINATION_X = 14;
    private static final int FLOOR_Y = 2;
    private static final int PLANE_Z = 8;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer connected;
    private final MinecraftPortal source;
    private final MinecraftPortal destination;
    private boolean cleaned;

    private MinecraftApertureShapeGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        connected = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "ShapeProbe");
        ServerPlayer player = connected.player();
        source = runtime.portals().create(player.getUUID(), helper.getLevel(), wall(SOURCE_X), PortalType.PORTAL, new Vec3(0, 0, 1));
        destination = runtime.portals().create(player.getUUID(), helper.getLevel(), wall(DESTINATION_X), PortalType.PORTAL, new Vec3(0, 0, 1));
        helper.assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "Shaped portal did not link");
    }

    public static void run(GameTestHelper helper) {
        new MinecraftApertureShapeGameTest(helper).start();
    }

    private void start() {
        RuntimeBaselineEnvironment.cleanupOnTeardown(this::cleanup);
        ServerPlayer player = connected.player();
        double corner = SOURCE_X + 0.3D;
        double center = SOURCE_X + EDGE * 0.5D;
        helper.startSequence().thenExecute(() -> {
            helper.assertTrue(runtime.portals().update(player, source.getId(), portal -> helper.assertTrue(portal.setApertureShape(CIRCLE),
                "The circle was refused by a 7x7 frame")), "The registry refused the shape update");
            int expected = ShapeRaster.of(PlaneShape.fit(Shapes.circle(1.0D), FitMode.CONTAIN, EDGE, EDGE), 4, 0.5D).insideCount();
            helper.assertTrue(source.getGeometry().getBlockPositions().size() == expected,
                "Effective cells " + source.getGeometry().getBlockPositions().size() + " differ from the Optics raster " + expected);
            helper.assertTrue(source.getBuiltGeometry().getBlockPositions().size() == EDGE * EDGE, "Built cells changed with the shape");
            assertDescriptor(player, expected);
            place(corner, FLOOR_Y, PLANE_Z + 0.1D);
        }).thenIdle(2).thenExecute(() -> {
            place(corner, FLOOR_Y, PLANE_Z + 0.9D);
            runtime.portals().tick();
            helper.assertTrue(!runtime.portals().travelling(player.getUUID()) && !arrived(player),
                "A segment through the corner cell crossed outside the circle");
        }).thenIdle(10).thenExecute(() -> {
            helper.assertTrue(!arrived(player), "The corner walker was teleported");
            place(corner, FLOOR_Y + EDGE + 3, PLANE_Z + 0.9D);
        }).thenIdle(2).thenExecute(() -> place(center, FLOOR_Y + EDGE + 3, PLANE_Z + 0.1D))
            .thenIdle(2).thenExecute(() -> place(center, FLOOR_Y, PLANE_Z + 0.1D))
            .thenIdle(2).thenExecute(() -> {
                place(center, FLOOR_Y, PLANE_Z + 0.9D);
                runtime.portals().tick();
                helper.assertTrue(runtime.portals().travelling(player.getUUID()) || arrived(player),
                    "A segment through the shape center did not cross");
            }).thenWaitUntil(() -> helper.assertTrue(arrived(player), "The center walker has not arrived at the destination")).thenExecute(() -> {
                LoggerFactory.getLogger("WormholesGameTest").info(
                    "WORMHOLES_GAME_TEST_PASS aperture_shape_runtime effective_cells descriptor_shape corner_refused center_crossed");
                cleanup();
            }).thenSucceed();
    }

    private void assertDescriptor(ServerPlayer player, int expected) {
        MinecraftClientViewPeer peer = new MinecraftClientViewPeer(player.getUUID(), "ShapeProbe", new Connection(PacketFlow.SERVERBOUND));
        peer.attach(player, new MinecraftProjectorPortalAccess(runtime));
        MinecraftClientViewPortalAccess access = new MinecraftClientViewPortalAccess(runtime);
        ApertureDescriptor sent = access.geometry(peer, source.getId(), new SessionPalette());
        helper.assertTrue(sent != null, "No ClientView descriptor was built for the shaped portal");
        helper.assertTrue(CIRCLE.equals(sent.shape()), "The ClientView descriptor lost the shape: " + sent.shape());
        helper.assertTrue(sent.openCellCount() == expected, "The ClientView mask is not the effective cell set");
        ApertureDescriptor effect = access.effectGeometry(peer, source.getId(), new SessionPalette());
        helper.assertTrue(effect != null && CIRCLE.equals(effect.shape()), "The effect descriptor lost the shape");
    }

    private boolean arrived(ServerPlayer player) {
        return player.position().x > helper.absolutePos(new BlockPos(DESTINATION_X - 1, 0, 0)).getX();
    }

    private void place(double x, double y, double z) {
        Vec3 position = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)).add(x, y, z);
        ServerPlayer player = connected.player();
        player.setPos(position);
        player.setYRot(0.0F);
        player.setXRot(0.0F);
    }

    private List<BlockPos> wall(int x) {
        List<BlockPos> cells = new ArrayList<>(EDGE * EDGE);
        for (int column = 0; column < EDGE; column++) {
            for (int row = 0; row < EDGE; row++) {
                cells.add(helper.absolutePos(new BlockPos(x + column, FLOOR_Y + row, PLANE_Z)));
            }
        }
        return cells;
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        runtime.portals().remove(source.getId());
        runtime.portals().remove(destination.getId());
        connected.close();
    }
}
