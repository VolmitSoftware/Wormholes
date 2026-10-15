package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftDyedItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class NetherReturnTerrainClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 1200;
    private static final int WINDOW_TICKS = 600;
    private static final int SETTLE_TICKS = 40;
    private static final int CROSS_SETTLE_TICKS = 60;
    private static final BlockPos NETHER = new BlockPos(0, 90, 0);
    private static final BlockPos CONTROL = new BlockPos(-400, 90, 0);
    private static final BlockPos TARGET = new BlockPos(400, 90, 0);
    private static final BlockPos SECOND = new BlockPos(800, 90, 0);

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean hidden = context.computeOnClient(minecraft -> ClientGui.hudHidden(minecraft));
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, FabricConnection.of(world));
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            client.runOnClient(minecraft -> {
                assertTrue(NativeTestWindow.hiddenAndUnfocused(minecraft) && NativeTestWindow.mouseReleased(minecraft),
                    "Nether return terrain requires a hidden, unfocused native GPU client with released mouse");
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.fov().set(70);
                minecraft.options.bobView().set(false);
                minecraft.options.ambientOcclusion().set(false);
                minecraft.options.fovEffectScale().set(0.0D);
                minecraft.options.framerateLimit().set(20);
                minecraft.options.particles().set(ParticleStatus.MINIMAL);
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
                ClientGui.clearToasts(minecraft);
            });
            world.getServer().runOnServer(server -> prepare(server, FabricConnection.of(world).getServerPlayer()));
            client.runOnClient(NetherReturnTerrainTap::open);
            Throwable primary = null;
            try {
                client.runOnClient(minecraft -> NetherReturnTerrainTap.requireShaderPack());
                control(client, world);
                nether(client, world);
                target(client, world, TARGET, "target");
                target(client, world, SECOND, "second");
            } catch (RuntimeException | Error error) {
                primary = error;
                throw error;
            } finally {
                finish(client, primary);
            }
            client.runOnClient(minecraft -> NetherReturnTerrainTap.verify());
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
            context.runOnClient(minecraft -> NetherReturnTerrainTap.stop());
            context.runOnClient(minecraft -> {
                if (ClientGui.hudHidden(minecraft) != hidden) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            context.restoreDefaultGameOptions();
        }
    }

    private static void finish(FabricSeamlessClient client, Throwable primary) {
        try {
            client.runOnClient(minecraft -> NetherReturnTerrainTap.stop());
            try {
                client.waitFor(minecraft -> NetherReturnTerrainTap.complete(), TIMEOUT_TICKS);
            } finally {
                client.runOnClient(minecraft -> NetherReturnTerrainTap.persist());
            }
        } catch (RuntimeException | Error error) {
            if (primary == null) {
                throw error;
            }
            primary.addSuppressed(error);
        }
    }

    private static void prepare(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        landmark(level, TARGET);
        door(level, TARGET);
        landmark(level, SECOND);
        door(level, SECOND);
        PortalViewFixture.netherFrame(level, NETHER);
    }

    private static void control(FabricSeamlessClient client, TestSingleplayerContext world) {
        flying(world, true);
        arrive(client, world, CONTROL);
        client.waitForChunksDownload();
        client.waitForChunksRender();
        client.waitTicks(SETTLE_TICKS);
        world.getServer().runOnServer(server -> landmark(server.overworld(), CONTROL));
        client.waitFor(minecraft -> received(minecraft.level, CONTROL, false), TIMEOUT_TICKS);
        capture(client, CONTROL, "control", NetherReturnTerrainTap.Mode.CONTROL);
        world.getServer().runOnServer(server -> door(server.overworld(), CONTROL));
        client.waitFor(minecraft -> received(minecraft.level, CONTROL, true), TIMEOUT_TICKS);
        capture(client, CONTROL, "control-door", NetherReturnTerrainTap.Mode.DOOR);
        flying(world, false);
    }

    private static void flying(TestSingleplayerContext world, boolean flying) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            player.getAbilities().flying = flying;
            player.onUpdateAbilities();
        });
    }

    private static void target(FabricSeamlessClient client, TestSingleplayerContext world, BlockPos origin, String name) {
        arrive(client, world, origin);
        client.waitFor(minecraft -> received(minecraft.level, origin, true), TIMEOUT_TICKS);
        capture(client, origin, name, NetherReturnTerrainTap.Mode.TARGET);
    }

    private static void arrive(FabricSeamlessClient client, TestSingleplayerContext world, BlockPos origin) {
        Vec3 camera = new Vec3(origin.getX() + NetherReturnTerrainTap.CAMERA_X, origin.getY(), origin.getZ() + NetherReturnTerrainTap.CAMERA_Z);
        world.getServer().runOnServer(server -> FabricConnection.of(world).getServerPlayer()
            .teleportTo(server.overworld(), camera.x, camera.y, camera.z, Set.of(), 0.0F, 0.0F, false));
        client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(camera) < 0.01D, TIMEOUT_TICKS);
        client.lookAt(0.0F, 0.0F);
    }

    private static void capture(FabricSeamlessClient client, BlockPos origin, String name, NetherReturnTerrainTap.Mode mode) {
        client.waitForChunksRender();
        client.runOnClient(minecraft -> NetherReturnTerrainTap.begin(name, origin, mode));
        client.waitFor(minecraft -> NetherReturnTerrainTap.done(name), WINDOW_TICKS);
        client.runOnClient(minecraft -> NetherReturnTerrainTap.require(name));
    }

    private static void nether(FabricSeamlessClient client, TestSingleplayerContext world) {
        Vec3 front = new Vec3(NETHER.getX() + 1.0D, NETHER.getY(), NETHER.getZ() + 5.0D);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            player.teleportTo(server.overworld(), front.x, front.y, front.z, Set.of(), 180.0F, 0.0F, false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(front) < 0.5D, TIMEOUT_TICKS);
        client.waitForChunksDownload();
        client.waitForChunksRender();
        client.waitTicks(SETTLE_TICKS);
        client.lookAt(180.0F, 0.0F);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            BlockPos base = NETHER.below();
            InteractionResult result = player.gameMode.useItemOn(player, server.overworld(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(base).add(0.0D, 0.5D, 0.0D), Direction.UP, base, false));
            assertTrue(result.consumesAction(), "flint and steel was not used on the obsidian frame: " + result);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        });
        int waited = 0;
        while (!world.getServer().computeOnServer(server -> SeamlessWalkThrough.lit(server, NETHER))) {
            assertTrue(waited++ < TIMEOUT_TICKS, "the ignited Nether frame was never converted");
            client.waitTicks(1);
        }
        client.waitFor(minecraft -> PortalViewFixture.drawsView(minecraft, "minecraft:the_nether"), TIMEOUT_TICKS);
        client.waitTicks(CROSS_SETTLE_TICKS);
        cross(client, Level.NETHER);
        client.waitTicks(CROSS_SETTLE_TICKS);
        float reverse = client.computeOnClient(minecraft -> minecraft.player.getYRot() + 180.0F);
        client.lookAt(reverse, 0.0F);
        client.waitTicks(CROSS_SETTLE_TICKS);
        cross(client, Level.OVERWORLD);
        client.waitTicks(CROSS_SETTLE_TICKS);
    }

    private static void cross(FabricSeamlessClient client, ResourceKey<Level> destination) {
        PortalViewFixture.cross(client);
        assertTrue(client.computeOnClient(minecraft -> minecraft.player.level().dimension() == destination && minecraft.level == minecraft.player.level()),
            "seamless Nether crossing did not arrive in " + destination.identifier());
    }

    private static boolean received(ClientLevel level, BlockPos origin, boolean door) {
        if (level == null) {
            return false;
        }
        for (int x = NetherReturnTerrainTap.MIN_X; x <= NetherReturnTerrainTap.MAX_X; x++) {
            for (int y = -1; y <= NetherReturnTerrainTap.WALL_TOP; y++) {
                if (level.getBlockState(origin.offset(x, y, NetherReturnTerrainTap.WALL_Z)) != tile(x, y)) {
                    return false;
                }
            }
            for (int z = NetherReturnTerrainTap.MIN_FLOOR_Z; z < NetherReturnTerrainTap.WALL_Z; z++) {
                if (level.getBlockState(origin.offset(x, -1, z)) != tile(x, z)) {
                    return false;
                }
            }
        }
        BlockPos lower = origin.offset(NetherReturnTerrainTap.DOOR_X, 0, NetherReturnTerrainTap.DOOR_Z);
        return door == level.getBlockState(lower).is(Blocks.DARK_OAK_DOOR) && door == level.getBlockState(lower.above()).is(Blocks.DARK_OAK_DOOR);
    }

    private static void landmark(ServerLevel level, BlockPos origin) {
        int width = NetherReturnTerrainTap.MAX_X - NetherReturnTerrainTap.MIN_X + 1;
        PortalViewFixture.fill(level, origin.offset(NetherReturnTerrainTap.MIN_X, 0, NetherReturnTerrainTap.MIN_FLOOR_Z), width, NetherReturnTerrainTap.WALL_TOP + 1,
            NetherReturnTerrainTap.WALL_Z - NetherReturnTerrainTap.MIN_FLOOR_Z, Blocks.AIR.defaultBlockState());
        for (int x = NetherReturnTerrainTap.MIN_X; x <= NetherReturnTerrainTap.MAX_X; x++) {
            for (int z = NetherReturnTerrainTap.MIN_FLOOR_Z; z < NetherReturnTerrainTap.WALL_Z; z++) {
                level.setBlockAndUpdate(origin.offset(x, -1, z), tile(x, z));
            }
            for (int y = -1; y <= NetherReturnTerrainTap.WALL_TOP; y++) {
                level.setBlockAndUpdate(origin.offset(x, y, NetherReturnTerrainTap.WALL_Z), tile(x, y));
            }
        }
    }

    private static void door(ServerLevel level, BlockPos origin) {
        BlockPos lower = origin.offset(NetherReturnTerrainTap.DOOR_X, 0, NetherReturnTerrainTap.DOOR_Z);
        BlockState door = Blocks.DARK_OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        level.setBlock(lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS);
        level.setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
    }

    private static BlockState tile(int column, int row) {
        return switch (NetherReturnTerrainTap.tile(column, row)) {
            case 0 -> MinecraftDyedItems.concreteBlock(DyeColor.RED).defaultBlockState();
            case 1 -> MinecraftDyedItems.concreteBlock(DyeColor.LIME).defaultBlockState();
            case 2 -> MinecraftDyedItems.concreteBlock(DyeColor.BLUE).defaultBlockState();
            default -> MinecraftDyedItems.concreteBlock(DyeColor.WHITE).defaultBlockState();
        };
    }
}
