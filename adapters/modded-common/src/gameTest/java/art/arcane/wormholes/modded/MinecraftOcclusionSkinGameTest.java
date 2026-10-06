package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.mixin.DoorDisplayDataAccess;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.frame.PortalCoordMap;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.claim.ProjectionClaimSet;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftOcclusionSkinGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final double SKIN_DEPTH = 0.125D;
    private static final double EPSILON = 1.0E-4D;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer observer;
    private final MinecraftPortal source;
    private final MinecraftPortal destination;

    private MinecraftOcclusionSkinGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        observer = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "OcclusionProbe");
        ServerPlayer player = observer.player();
        source = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(2), PortalType.PORTAL, new Vec3(0, 0, -1));
        destination = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(14), PortalType.PORTAL, new Vec3(0, 0, -1));
        helper.assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "Occlusion portals did not link");
        source.setRenderMode(ProjectionRenderMode.VENTICULAR);
        Vec3d origin = source.getOrigin();
        Vec3d eye = origin.add(source.getFrame().getNormal().toVector().multiply(3.0D));
        player.setPos(eye.x(), eye.y() - player.getEyeHeight(), eye.z());
    }

    public static void run(GameTestHelper helper) {
        MinecraftOcclusionSkinGameTest test = new MinecraftOcclusionSkinGameTest(helper);
        helper.startSequence().thenExecute(test::verify).thenSucceed();
    }

    private void verify() {
        try {
            List<String> culled = new ArrayList<>(2);
            if (!projectedBehind(Blocks.STONE_SLAB.defaultBlockState(), Blocks.EMERALD_BLOCK.defaultBlockState())) {
                culled.add("bottom slab");
            }
            if (!projectedBehind(Blocks.SNOW.defaultBlockState(), Blocks.LAPIS_BLOCK.defaultBlockState())) {
                culled.add("single snow layer");
            }
            helper.assertTrue(culled.isEmpty(), "Venticular culled blocks visible behind " + String.join(" and ", culled));
            verifySkinFace();
            LOGGER.info("WORMHOLES_GAME_TEST_PASS occlusion_skin_runtime venticular_bottom_slab venticular_snow_layer skin_face_depth skin_face_span");
        } finally {
            runtime.portals().remove(source.getId());
            runtime.portals().remove(destination.getId());
            observer.close();
        }
    }

    private boolean projectedBehind(BlockState cover, BlockState marker) {
        ServerLevel level = helper.getLevel();
        BlockPos markerCell = helper.absolutePos(new BlockPos(3, 3, 8));
        BlockPos coverCell = helper.absolutePos(new BlockPos(3, 3, 6));
        clearScene(level);
        for (int x = -1; x <= 1; x++) {
            setRemote(level, coverCell.offset(x, -1, 0), Blocks.STONE.defaultBlockState());
            setRemote(level, coverCell.offset(x, 0, 0), cover);
        }
        setRemote(level, markerCell, marker);
        for (int x = -1; x <= 1; x++) {
            helper.assertTrue(remoteState(level, coverCell.offset(x, 0, 0)).is(cover.getBlock()), "Occlusion cover row did not stay in place");
        }
        try (MinecraftProjectionWorldView view = MinecraftProjectionWorldView.uncached(runtime, level);
             MinecraftPortalProjector projector = new MinecraftPortalProjector(runtime,
                 new MinecraftPortalProjector.Context(observer.player(), source, ignored -> view, new MinecraftProjectorPortalAccess(runtime), null))) {
            MinecraftPortalProjector.Result result = projector.update(level.getGameTime(), Long.MAX_VALUE);
            helper.assertTrue(result == MinecraftPortalProjector.Result.READY, "Venticular projection pass did not complete: " + result);
            ProjectedBlockClaim<BlockState, ContentView<BlockState, BlockState>> coverClaim = projector.claimDelta().claims()
                .get(CellKeys.pack(coverCell.getX(), coverCell.getY(), coverCell.getZ()));
            helper.assertTrue(coverClaim != null && coverClaim.getData().is(cover.getBlock()),
                "Venticular projection did not place the " + cover.getBlock().getName().getString() + " row in front of the marker");
            ProjectedBlockClaim<BlockState, ContentView<BlockState, BlockState>> markerClaim = projector.claimDelta().claims()
                .get(CellKeys.pack(markerCell.getX(), markerCell.getY(), markerCell.getZ()));
            return markerClaim != null && markerClaim.getData().is(marker.getBlock());
        } finally {
            clearScene(level);
        }
    }

    private void verifySkinFace() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockState, ContentView<BlockState, BlockState>>> claims = new ProjectionClaimSet<>();
        LongOpenHashSet staged = new LongOpenHashSet();
        source.setSurfaceSkin("minecraft:glass");
        observer.drainPackets();
        List<Object> packets;
        try (MinecraftPortalSurfaces surfaces = new MinecraftPortalSurfaces(runtime,
            new MinecraftPortalSurfaces.Context(observer.player(), claims, staged))) {
            surfaces.update(List.of(source), new MinecraftProjectorPortalAccess(runtime), 1);
            packets = observer.drainPackets();
        } finally {
            source.setSurfaceSkin("");
        }
        ClientboundAddEntityPacket spawn = null;
        int displays = 0;
        for (Object packet : packets) {
            if (packet instanceof ClientboundAddEntityPacket added && added.getType() == EntityTypes.BLOCK_DISPLAY) {
                spawn = added;
                displays++;
            }
        }
        helper.assertTrue(displays == 1, "Glass skin on a 3x3 opening did not use one display face: " + displays);
        Vector3fc scale = null;
        Vector3fc translation = null;
        for (Object packet : packets) {
            if (packet instanceof ClientboundSetEntityDataPacket data && data.id() == spawn.getId()) {
                for (SynchedEntityData.DataValue<?> value : data.packedItems()) {
                    if (value.id() == DoorDisplayDataAccess.wormholesScale().id()) {
                        scale = (Vector3fc) value.value();
                    } else if (value.id() == DoorDisplayDataAccess.wormholesTranslation().id()) {
                        translation = (Vector3fc) value.value();
                    }
                }
            }
        }
        helper.assertTrue(scale != null && translation != null, "Skin display metadata did not carry its transformation");
        Box area = source.getGeometry().getArea();
        double plane = source.getOrigin().z();
        helper.assertTrue(near(scale.z(), SKIN_DEPTH), "Skin face depth along the portal normal is " + scale.z() + " instead of " + SKIN_DEPTH);
        helper.assertTrue(near(spawn.getZ() + translation.z() + scale.z() / 2.0D, plane), "Skin face is not centered on the portal plane");
        helper.assertTrue(near(scale.x(), area.sizeX()) && near(scale.y(), area.sizeY()),
            "Skin face " + scale.x() + "x" + scale.y() + " does not span the " + area.sizeX() + "x" + area.sizeY() + " opening");
        helper.assertTrue(near(spawn.getX() + translation.x(), area.getXa()) && near(spawn.getY() + translation.y(), area.getYa()),
            "Skin face is not aligned with the opening corner");
    }

    private void setRemote(ServerLevel level, BlockPos local, BlockState state) {
        level.setBlock(remote(local), state, Block.UPDATE_CLIENTS);
    }

    private BlockState remoteState(ServerLevel level, BlockPos local) {
        return level.getBlockState(remote(local));
    }

    private BlockPos remote(BlockPos local) {
        Vec3d from = source.getOrigin();
        Vec3d to = destination.getOrigin();
        double[] transformed = new double[3];
        PortalCoordMap.transformPointInto(local.getX() + 0.5D, local.getY() + 0.5D, local.getZ() + 0.5D, from.x(), from.y(), from.z(),
            to.x(), to.y(), to.z(), source.getFrame(), destination.getFrame(), transformed);
        return BlockPos.containing(transformed[0], transformed[1], transformed[2]);
    }

    private void clearScene(ServerLevel level) {
        for (BlockPos local : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(1, 1, 6)), helper.absolutePos(new BlockPos(5, 4, 10)))) {
            level.setBlock(remote(local), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private List<BlockPos> cells(int minX) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = minX; x < minX + 3; x++) {
            for (int y = 2; y < 5; y++) {
                cells.add(helper.absolutePos(new BlockPos(x, y, 4)));
            }
        }
        return cells;
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }
}
