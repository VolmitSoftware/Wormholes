package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MinecraftGravityPoseGameTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000006a71");

    private MinecraftGravityPoseGameTest() {
    }

    public static void verify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(48, 24, 12)));
        int rotations = 0;
        for (int index = 0; index < 48; index++) {
            AxisPermutation orientation = AxisPermutation.ofIndex(index);
            if (orientation.reflects()) {
                continue;
            }
            GravityFrame frame = MinecraftGravity.fromIndex(index);
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(PLAYER_ID, "PoseProbe"), false);
            PosePlayer player = new PosePlayer(level, cookie);
            Map<BlockPos, BlockState> replaced = new LinkedHashMap<>();
            try {
                MinecraftGravity.set(player, frame);
                verifyWorldUpObstruction(helper, player, origin, frame, replaced);
                verifyLanding(helper, player, origin, frame, replaced);
                verifyClearance(helper, player, origin, frame, replaced);
                rotations++;
            } finally {
                for (Map.Entry<BlockPos, BlockState> entry : replaced.entrySet()) {
                    level.setBlock(entry.getKey(), entry.getValue(), 3);
                }
            }
        }
        helper.assertTrue(rotations == 24, "Did not cover every gravity rotation");
        LoggerFactory.getLogger("WormholesGameTest").info(
            "WORMHOLES_GAME_TEST_PASS gravity_pose_runtime rotations=24 world_up_obstruction landing standing crouching crawling clearance");
    }

    private static void verifyWorldUpObstruction(GameTestHelper helper, PosePlayer player, Vec3 origin, GravityFrame frame,
                                                Map<BlockPos, BlockState> replaced) {
        if (MinecraftGravity.world(frame, new Vec3(0, 1, 0)).y != 0.0D) {
            return;
        }
        player.setPos(origin.add(0.5D, 0.2D, 0.5D));
        BlockPos obstacle = BlockPos.containing(origin.add(0, 1, 0));
        replace(player.level(), obstacle, Blocks.STONE.defaultBlockState(), replaced);
        helper.assertTrue(!player.level().noCollision(player, player.getDimensions(Pose.STANDING).makeBoundingBox(player.position())),
            "World-up obstruction did not intersect the upright standing probe");
        player.updatePlayerPose();
        helper.assertTrue(player.getPose() == Pose.STANDING,
            "A world-up obstruction outside the rotated body forced " + player.getPose() + " under " + frame);
        replace(player.level(), obstacle, Blocks.AIR.defaultBlockState(), replaced);
    }

    private static void verifyLanding(GameTestHelper helper, PosePlayer player, Vec3 origin, GravityFrame frame,
                                      Map<BlockPos, BlockState> replaced) {
        layer(player, origin, frame, -1, Blocks.STONE.defaultBlockState(), replaced);
        player.setPos(origin.add(MinecraftGravity.world(frame, new Vec3(0.5D, 2.5D, 0.5D))));
        player.setDeltaMovement(MinecraftGravity.world(frame, new Vec3(0, -3, 0)));
        player.move(MoverType.SELF, player.getDeltaMovement());
        Vec3 feet = MinecraftGravity.local(frame, player.position().subtract(origin));
        helper.assertTrue(player.onGround() && Math.abs(feet.y) < 1.0E-6D,
            "Player did not land on the rotated floor under " + frame + ": " + feet);
        helper.assertTrue(player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING),
            "Standing pose cannot fit after landing on the rotated floor under " + frame);
        player.updatePlayerPose();
        helper.assertTrue(player.getPose() == Pose.STANDING, "Landing forced " + player.getPose() + " under " + frame);
        player.setShiftKeyDown(true);
        player.updatePlayerPose();
        helper.assertTrue(player.getPose() == Pose.CROUCHING, "Crouch input did not select crouching under " + frame);
        player.setShiftKeyDown(false);
        player.updatePlayerPose();
        helper.assertTrue(player.getPose() == Pose.STANDING, "Releasing crouch did not restore standing under " + frame);
    }

    private static void verifyClearance(GameTestHelper helper, PosePlayer player, Vec3 origin, GravityFrame frame,
                                        Map<BlockPos, BlockState> replaced) {
        layer(player, origin, frame, 2, Blocks.STONE.defaultBlockState(), replaced);
        player.setPos(origin.add(MinecraftGravity.world(frame, new Vec3(0.5D, 0.4D, 0.5D))));
        player.updatePlayerPose();
        helper.assertTrue(player.getPose() == Pose.CROUCHING, "Low rotated ceiling did not require crouching under " + frame);
        layer(player, origin, frame, 2, Blocks.AIR.defaultBlockState(), replaced);
        layer(player, origin, frame, 1, Blocks.STONE.defaultBlockState(), replaced);
        player.setPos(origin.add(MinecraftGravity.world(frame, new Vec3(0.5D, 0, 0.5D))));
        player.updatePlayerPose();
        helper.assertTrue(player.getPose() == Pose.SWIMMING, "One-block rotated clearance did not require crawling under " + frame);
        layer(player, origin, frame, 1, Blocks.AIR.defaultBlockState(), replaced);
        player.updatePlayerPose();
        helper.assertTrue(player.getPose() == Pose.STANDING, "Cleared headroom did not restore standing under " + frame);
    }

    private static void layer(PosePlayer player, Vec3 origin, GravityFrame frame, int height, BlockState state,
                              Map<BlockPos, BlockState> replaced) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Vec3 center = origin.add(MinecraftGravity.world(frame, new Vec3(x + 0.5D, height + 0.5D, z + 0.5D)));
                replace(player.level(), BlockPos.containing(center), state, replaced);
            }
        }
    }

    private static void replace(ServerLevel level, BlockPos position, BlockState state, Map<BlockPos, BlockState> replaced) {
        replaced.putIfAbsent(position, level.getBlockState(position));
        level.setBlock(position, state, 3);
    }

    private static final class PosePlayer extends ServerPlayer {
        private PosePlayer(ServerLevel level, CommonListenerCookie cookie) {
            super(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        }

        @Override
        public void updatePlayerPose() {
            super.updatePlayerPose();
        }

        @Override
        public boolean canPlayerFitWithinBlocksAndEntitiesWhen(Pose pose) {
            return super.canPlayerFitWithinBlocksAndEntitiesWhen(pose);
        }
    }
}
