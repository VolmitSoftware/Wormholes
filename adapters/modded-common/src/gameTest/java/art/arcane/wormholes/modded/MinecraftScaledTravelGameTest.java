package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.PortalType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftScaledTravelGameTest {
    private static final int SMALL = 3;
    private static final int LARGE = 9;
    private static final int SMALL_X = 2;
    private static final int LARGE_X = 12;
    private static final int FLOOR_Y = 2;
    private static final int PLANE_Z = 4;
    private static final int COOLDOWN_TICKS = 30;
    private static final double ENTRY_OFFSET = 1.0D;
    private static final double TOLERANCE = 1.0E-3D;
    private static final ScaleRule RATIO = ScaleRule.ratio(0.25D, 4.0D);

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftScaleAccess scale = MinecraftScaleAccess.scaleAttribute();
    private final MinecraftGameTestPlayer connected;
    private final MinecraftPortal small;
    private final MinecraftPortal large;
    private Pig pig;
    private boolean cleaned;

    private MinecraftScaledTravelGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        connected = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "ScaleProbe");
        ServerPlayer player = connected.player();
        small = runtime.portals().create(player.getUUID(), helper.getLevel(), wall(SMALL_X, SMALL), PortalType.PORTAL, new Vec3(0, 0, 1));
        large = runtime.portals().create(player.getUUID(), helper.getLevel(), wall(LARGE_X, LARGE), PortalType.PORTAL, new Vec3(0, 0, 1));
    }

    public static void run(GameTestHelper helper) {
        new MinecraftScaledTravelGameTest(helper).start();
    }

    private void start() {
        RuntimeBaselineEnvironment.cleanupOnTeardown(this::cleanup);
        ServerPlayer player = connected.player();
        Vec3d[] entry = new Vec3d[1];
        helper.startSequence().thenExecute(() -> {
            helper.assertTrue(runtime.portals().link(player, small.getId(), large.getId()), "The 3x3 portal did not link to the 9x9");
            helper.assertTrue(runtime.portals().link(player, large.getId(), small.getId()), "The 9x9 portal did not link back");
            setRule(small, RATIO);
            setRule(large, RATIO);
            entry[0] = approach(small, SMALL_X, SMALL);
        }).thenIdle(2).thenExecute(() -> cross(small, SMALL_X, SMALL))
            .thenWaitUntil(() -> helper.assertTrue(near(player, LARGE_X, LARGE), "The player did not grow through to the 9x9"))
            .thenExecute(() -> {
                assertFactor(player, 3.0D, "after the 3x3 to 9x9 crossing");
                AttributeModifier modifier = player.getAttribute(Attributes.SCALE).getModifier(MinecraftScaleAccess.ID);
                helper.assertTrue(modifier != null && Math.abs(modifier.amount() - 2.0D) < TOLERANCE, "The portal modifier is not amount 2.0: " + modifier);
                double arrivedOffset = rightOffset(player, large);
                helper.assertTrue(Math.abs(arrivedOffset - 3.0D * entry[0].x()) < 1.0E-2D,
                    "The arrival offset " + arrivedOffset + " is not three times the entry offset " + entry[0].x());
            }).thenExecute(this::away).thenIdle(COOLDOWN_TICKS).thenExecute(() -> entry[0] = approach(large, LARGE_X, LARGE))
            .thenIdle(2).thenExecute(() -> cross(large, LARGE_X, LARGE))
            .thenWaitUntil(() -> helper.assertTrue(near(player, SMALL_X, SMALL), "The player did not shrink back through the 3x3"))
            .thenExecute(() -> {
                assertFactor(player, 1.0D, "after the return crossing");
                helper.assertTrue(player.getAttribute(Attributes.SCALE).getModifier(MinecraftScaleAccess.ID) == null,
                    "The return crossing left the portal modifier on the player");
                setRule(small, ScaleRule.motion());
            }).thenExecute(this::away).thenIdle(COOLDOWN_TICKS).thenExecute(() -> entry[0] = approach(small, SMALL_X, SMALL))
            .thenIdle(2).thenExecute(() -> cross(small, SMALL_X, SMALL))
            .thenWaitUntil(() -> helper.assertTrue(near(player, LARGE_X, LARGE), "The motion crossing did not arrive"))
            .thenExecute(() -> {
                assertFactor(player, 1.0D, "after a motion crossing");
                double arrivedOffset = rightOffset(player, large);
                helper.assertTrue(Math.abs(arrivedOffset - 3.0D * entry[0].x()) < 1.0E-2D,
                    "The motion arrival offset " + arrivedOffset + " is not three times the entry offset " + entry[0].x());
                setRule(small, RATIO);
            }).thenExecute(this::away).thenIdle(COOLDOWN_TICKS).thenExecute(() -> approach(small, SMALL_X, SMALL))
            .thenIdle(2).thenExecute(() -> cross(small, SMALL_X, SMALL))
            .thenWaitUntil(() -> helper.assertTrue(near(player, LARGE_X, LARGE), "The first loop pass did not arrive"))
            .thenExecute(() -> assertFactor(player, 3.0D, "after the first loop pass"))
            .thenExecute(this::away).thenIdle(COOLDOWN_TICKS).thenExecute(() -> approach(small, SMALL_X, SMALL))
            .thenIdle(2).thenExecute(() -> cross(small, SMALL_X, SMALL))
            .thenWaitUntil(() -> helper.assertTrue(near(player, LARGE_X, LARGE), "The second loop pass did not arrive"))
            .thenExecute(() -> assertFactor(player, 4.0D, "after the second loop pass"))
            .thenExecute(this::away).thenIdle(COOLDOWN_TICKS).thenExecute(() -> approach(small, SMALL_X, SMALL))
            .thenIdle(2).thenExecute(() -> cross(small, SMALL_X, SMALL))
            .thenWaitUntil(() -> helper.assertTrue(near(player, LARGE_X, LARGE), "The third loop pass did not arrive"))
            .thenExecute(() -> {
                assertFactor(player, 4.0D, "after the clamped loop pass");
                pig = EntityTypes.PIG.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                helper.assertTrue(pig != null, "Could not create the pig");
                pig.setNoAi(true);
                Vec3 at = player.position().add(2.0D, 0.0D, 0.0D);
                pig.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
                helper.getLevel().addFreshEntity(pig);
                helper.assertTrue(scale.scale(pig, 2.0D), "Could not grow the pig");
                CommandSourceStack source = runtime.server().createCommandSourceStack().withLevel(helper.getLevel())
                    .withPosition(player.position()).withSuppressedOutput();
                int restored = command("wormholes scale reset all 16", source);
                helper.assertTrue(restored == 2, "The reset reported " + restored + " entities instead of 2");
                assertFactor(player, 1.0D, "after the reset");
                helper.assertTrue(Math.abs(scale.scale(pig) - 1.0D) < TOLERANCE, "The reset left the pig grown");
                LoggerFactory.getLogger("WormholesGameTest").info(
                    "WORMHOLES_GAME_TEST_PASS scaled_travel_runtime ratio_grows_three return_restores motion_keeps_size loop_clamps reset_all");
                cleanup();
            }).thenSucceed();
    }

    private static int command(String input, CommandSourceStack source) {
        try {
            return WormholesGameTests.RUNTIME.server().getCommands().getDispatcher().execute(input, source);
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException("Could not run " + input, failure);
        }
    }

    private void setRule(MinecraftPortal portal, ScaleRule rule) {
        ServerPlayer player = connected.player();
        helper.assertTrue(runtime.portals().update(player, portal.getId(), target -> target.setScaleRule(rule)), "The registry refused the scale rule");
        helper.assertTrue(rule.equals(portal.getScaleRule()), "The portal did not keep the scale rule " + rule);
    }

    private Vec3d approach(MinecraftPortal portal, int x, int edge) {
        ServerPlayer player = connected.player();
        double offset = ENTRY_OFFSET * edge / SMALL;
        Vec3 start = position(x + edge * 0.5D + offset, FLOOR_Y, PLANE_Z + 0.1D);
        player.connection.teleport(start.x, start.y, start.z, 0.0F, 0.0F);
        connected.acknowledgePosition();
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        return new Vec3d(rightOffset(player, portal), 0.0D, 0.0D);
    }

    private void away() {
        ServerPlayer player = connected.player();
        Vec3 aside = position(SMALL_X, FLOOR_Y + 20.0D, PLANE_Z - 6.0D);
        player.connection.teleport(aside.x, aside.y, aside.z, 0.0F, 0.0F);
        connected.acknowledgePosition();
    }

    private void cross(MinecraftPortal portal, int x, int edge) {
        ServerPlayer player = connected.player();
        double offset = ENTRY_OFFSET * edge / SMALL;
        player.setPos(position(x + edge * 0.5D + offset, FLOOR_Y, PLANE_Z + 0.9D));
        runtime.portals().tick();
    }

    private double rightOffset(ServerPlayer player, MinecraftPortal portal) {
        Vec3d origin = portal.getOrigin();
        Vec3d right = portal.getFrame().getRight().toVector();
        return (player.getX() - origin.x()) * right.x() + (player.getY() - origin.y()) * right.y() + (player.getZ() - origin.z()) * right.z();
    }

    private void assertFactor(ServerPlayer player, double expected, String context) {
        double factor = scale.scale(player);
        helper.assertTrue(Math.abs(factor - expected) < TOLERANCE, "Scale factor " + factor + " is not " + expected + " " + context);
        helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.SCALE) - expected) < TOLERANCE,
            "Scale attribute " + player.getAttributeValue(Attributes.SCALE) + " is not " + expected + " " + context);
    }

    private boolean near(ServerPlayer player, int x, int edge) {
        double localX = player.getX() - helper.absolutePos(BlockPos.ZERO).getX();
        return localX >= x - 1.0D && localX <= x + edge + 1.0D;
    }

    private Vec3 position(double x, double y, double z) {
        return Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)).add(x, y, z);
    }

    private List<BlockPos> wall(int x, int edge) {
        List<BlockPos> cells = new ArrayList<>(edge * edge);
        for (int column = 0; column < edge; column++) {
            for (int row = 0; row < edge; row++) {
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
        if (pig != null) {
            pig.discard();
        }
        scale.reset(connected.player());
        runtime.portals().remove(small.getId());
        runtime.portals().remove(large.getId());
        connected.close();
    }
}
