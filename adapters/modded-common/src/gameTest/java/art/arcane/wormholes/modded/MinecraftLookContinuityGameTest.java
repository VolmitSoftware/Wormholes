package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftLookContinuityGameTest {
    private static final int EDGE = 3;
    private static final int FLOOR_Y = 3;
    private static final int SOURCE_X = 2;
    private static final int UPWARD_X = 12;
    private static final int WALL_X = 22;
    private static final int WALL_Z = 3;
    private static final float YAW = 30.0F;
    private static final float BODY_YAW = 10.0F;
    private static final float TOLERANCE = 0.5F;
    private static final int COOLDOWN_TICKS = 30;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer connected;
    private final MinecraftPortal source;
    private final MinecraftPortal upward;
    private final MinecraftPortal wall;
    private final List<Entity> spawned = new ArrayList<>();
    private final List<Float> departureOffsets = new ArrayList<>();
    private boolean cleaned;
    private double farthest;

    private MinecraftLookContinuityGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        connected = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "LookProbe");
        ServerPlayer player = connected.player();
        source = runtime.portals().create(player.getUUID(), helper.getLevel(), floor(SOURCE_X), PortalType.PORTAL, new Vec3(0, 1, 0));
        upward = runtime.portals().create(player.getUUID(), helper.getLevel(), floor(UPWARD_X), PortalType.PORTAL, new Vec3(0, -1, 0));
        wall = runtime.portals().create(player.getUUID(), helper.getLevel(), wall(), PortalType.PORTAL, new Vec3(0, 0, 1));
    }

    public static void run(GameTestHelper helper) {
        new MinecraftLookContinuityGameTest(helper).start();
    }

    private void start() {
        RuntimeBaselineEnvironment.cleanupOnTeardown(this::cleanup);
        ServerPlayer player = connected.player();
        LookTransfer upwardLook = predicted(upward, YAW, 90.0F);
        LookTransfer wallLook = predicted(wall, 0.0F, 90.0F);
        helper.startSequence().thenExecute(() -> {
            helper.assertTrue(upwardLook.pitch() == -90.0F && Math.abs(upwardLook.roll()) < TOLERANCE,
                "The upward pair should carry a straight-down look to straight up without roll: " + upwardLook);
            helper.assertTrue(runtime.portals().link(player, source.getId(), upward.getId()), "The floor portal did not link upward");
            placePlayer(FLOOR_Y + 1.1D, YAW, 90.0F);
        }).thenIdle(2).thenExecute(() -> crossPlayer(YAW, 90.0F))
            .thenWaitUntil(() -> {
                String position = where(player);
                helper.assertTrue(near(player, UPWARD_X), "The player did not arrive above the upward exit; " + position);
            })
            .thenExecute(() -> {
                helper.assertTrue(Math.abs(player.getXRot() + 90.0F) <= TOLERANCE, "Arrival pitch " + player.getXRot() + " is not straight up");
                helper.assertTrue(Math.abs(Angles.unwrap(player.getYRot() - upwardLook.yaw(), 0.0F)) <= TOLERANCE,
                    "Arrival yaw " + player.getYRot() + " differs from the look transfer " + upwardLook.yaw());
                helper.assertTrue(Math.abs(player.xRotO + 90.0F) <= TOLERANCE, "Previous pitch " + player.xRotO + " was not carried");
                helper.assertTrue(Math.abs(player.yRotO - player.getYRot()) <= TOLERANCE, "Previous yaw " + player.yRotO + " streaks from " + player.getYRot());
                helper.assertTrue(Math.abs(Angles.unwrap(player.yHeadRot - player.getYRot(), 0.0F)) <= TOLERANCE, "Head yaw left the look");
                helper.assertTrue(Math.abs(Math.abs(Angles.unwrap(player.yBodyRot - player.yHeadRot, 0.0F)) - Math.abs(YAW - BODY_YAW)) <= TOLERANCE,
                    "Body yaw " + player.yBodyRot + " does not keep its offset under the head " + player.yHeadRot);
                helper.assertTrue(runtime.portals().link(player, source.getId(), wall.getId()), "The floor portal did not relink to the wall");
            }).thenIdle(COOLDOWN_TICKS).thenExecute(() -> placePlayer(FLOOR_Y + 1.1D, 0.0F, 90.0F))
            .thenIdle(2).thenExecute(() -> crossPlayer(0.0F, 90.0F))
            .thenWaitUntil(() -> helper.assertTrue(near(player, WALL_X), "The player did not arrive at the wall exit; " + where(player)))
            .thenExecute(() -> {
                Vec3d look = Angles.direction(player.getYRot(), player.getXRot());
                Vec3d exit = Angles.direction(wallLook.yaw(), wallLook.pitch());
                helper.assertTrue(Math.abs(player.getXRot()) <= TOLERANCE, "A straight-down look through a floor-to-wall pair arrived at pitch "
                    + player.getXRot());
                helper.assertTrue(look.subtract(exit).length() < 0.02D, "The floor-to-wall arrival " + look + " does not look along the exit " + exit);
                helper.assertTrue(runtime.portals().link(player, source.getId(), upward.getId()), "The floor portal did not relink upward");
                placePlayer(FLOOR_Y + 40.0D, 0.0F, 0.0F);
                spawned.add(spawn(EntityTypes.ARMOR_STAND, 0.0D));
                spawned.add(spawn(EntityTypes.PIG, 1.0D));
            }).thenIdle(2).thenExecute(() -> {
                for (Entity entity : spawned) {
                    LivingEntity living = (LivingEntity) entity;
                    departureOffsets.add(Math.abs(Angles.unwrap(living.yBodyRot - living.yHeadRot, 0.0F)));
                    move(entity, FLOOR_Y + 0.2D);
                }
                runtime.portals().tick();
            }).thenWaitUntil(() -> {
                for (Entity entity : spawned) {
                    helper.assertTrue(near(entity, UPWARD_X), entity.getType() + " did not arrive above the upward exit; " + where(entity));
                }
            }).thenExecute(() -> {
                for (int index = 0; index < spawned.size(); index++) {
                    Entity entity = spawned.get(index);
                    LivingEntity living = (LivingEntity) entity;
                    float offset = Math.abs(Angles.unwrap(living.yBodyRot - living.yHeadRot, 0.0F));
                    helper.assertTrue(Math.abs(offset - departureOffsets.get(index)) <= TOLERANCE, entity.getType() + " body " + living.yBodyRot
                        + " and head " + living.yHeadRot + " left their departure offset " + departureOffsets.get(index));
                    helper.assertTrue(Math.abs(Angles.unwrap(living.yHeadRot - entity.getYRot(), 0.0F)) <= TOLERANCE,
                        entity.getType() + " head " + living.yHeadRot + " left its look " + entity.getYRot());
                }
                LoggerFactory.getLogger("WormholesGameTest").info(
                    "WORMHOLES_GAME_TEST_PASS look_continuity_runtime straight_up floor_to_wall body_head_entities");
                cleanup();
            }).thenSucceed();
    }

    private LookTransfer predicted(MinecraftPortal exit, float yaw, float pitch) {
        PlaneCrossing crossing = new PlaneCrossing(source.getFrame().view(true), source.getOrigin(), source.getOrigin(),
            new Vec3d(0.0D, -0.4D, 0.0D), Angles.direction(yaw, pitch), true);
        return ArrivalOrientation.transfer(crossing, LookTransfer.cameraUp(yaw, pitch), exit.getFrame(), OrientationRule.FRAME, false);
    }

    private void placePlayer(double y, float yaw, float pitch) {
        ServerPlayer player = connected.player();
        Vec3 position = position(SOURCE_X + 1.5D, y, 1.5D);
        player.connection.teleport(position.x, position.y, position.z, yaw, pitch);
        connected.acknowledgePosition();
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
        player.yHeadRotO = yaw;
        player.yBodyRot = yaw - (YAW - BODY_YAW);
        player.yBodyRotO = player.yBodyRot;
    }

    private void crossPlayer(float yaw, float pitch) {
        ServerPlayer player = connected.player();
        player.setPos(position(SOURCE_X + 1.5D, FLOOR_Y + 0.2D, 1.5D));
        player.setYRot(yaw);
        player.setXRot(pitch);
        runtime.portals().tick();
    }

    private Entity spawn(EntityType<? extends LivingEntity> type, double offset) {
        LivingEntity entity = type.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        helper.assertTrue(entity != null, "Could not create " + type);
        entity.setNoGravity(true);
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
        }
        Vec3 position = position(SOURCE_X + 0.5D + offset, FLOOR_Y + 1.1D, 1.5D);
        entity.snapTo(position.x, position.y, position.z, YAW, 0.0F);
        entity.setYHeadRot(YAW);
        entity.yHeadRotO = YAW;
        entity.yBodyRot = BODY_YAW;
        entity.yBodyRotO = BODY_YAW;
        helper.getLevel().addFreshEntity(entity);
        return entity;
    }

    private void move(Entity entity, double y) {
        Vec3 current = entity.position();
        entity.setPos(current.x, Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)).y + y, current.z);
    }

    private boolean near(Entity entity, int x) {
        double localX = entity.position().x - helper.absolutePos(BlockPos.ZERO).getX();
        return localX >= x - 1.0D && localX <= x + EDGE + 1.0D;
    }

    private String where(Entity entity) {
        Vec3 local = entity.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
        farthest = Math.max(farthest, local.x);
        return "at " + local + " farthest x " + farthest + " travelling " + runtime.portals().travelling(entity.getUUID());
    }

    private Vec3 position(double x, double y, double z) {
        return Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)).add(x, y, z);
    }

    private List<BlockPos> floor(int x) {
        List<BlockPos> cells = new ArrayList<>(EDGE * EDGE);
        for (int column = 0; column < EDGE; column++) {
            for (int row = 0; row < EDGE; row++) {
                cells.add(helper.absolutePos(new BlockPos(x + column, FLOOR_Y, row)));
            }
        }
        return cells;
    }

    private List<BlockPos> wall() {
        List<BlockPos> cells = new ArrayList<>(EDGE * EDGE);
        for (int column = 0; column < EDGE; column++) {
            for (int row = 0; row < EDGE; row++) {
                cells.add(helper.absolutePos(new BlockPos(WALL_X + column, FLOOR_Y + row, WALL_Z)));
            }
        }
        return cells;
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        for (Entity entity : spawned) {
            entity.discard();
        }
        runtime.portals().remove(source.getId());
        runtime.portals().remove(upward.getId());
        runtime.portals().remove(wall.getId());
        connected.close();
    }
}
