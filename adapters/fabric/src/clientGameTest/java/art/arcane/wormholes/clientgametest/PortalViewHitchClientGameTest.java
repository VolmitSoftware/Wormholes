package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.modded.MinecraftDoorItems;
import art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class PortalViewHitchClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int TIMEOUT_TICKS = 1200;
    private static final int VIEW_TICKS = 160;
    private static final int WALK_TICKS = 80;
    private static final int HOLD_TICKS = 100;
    private static final int CROSS_SETTLE_TICKS = 60;
    private static final double ORBIT_BLOCKS = 4.5D;
    private static final double DOOR_ORBIT_BLOCKS = 3.0D;
    private static final BlockPos NETHER = new BlockPos(0, 90, 0);
    private static final BlockPos PERSONAL = new BlockPos(400, 90, 0);
    private static final BlockPos PUBLIC = new BlockPos(800, 90, 0);
    private static final BlockPos PAIR_FIRST = new BlockPos(1200, 90, 0);
    private static final BlockPos PAIR_SECOND = new BlockPos(1232, 90, 24);

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean shaders = FabricLoader.getInstance().isModLoaded("iris");
        String mode = shaders ? "shaders" : "plain";
        boolean hidden = context.computeOnClient(minecraft -> ClientGui.hudHidden(minecraft));
        ClientViewTestConfig.enableSeamless(true);
        List<FrameHitchTap.Report> reports = new ArrayList<>(10);
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, FabricConnection.of(world));
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            client.runOnClient(minecraft -> {
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
                ClientGui.clearToasts(minecraft);
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.fov().set(70);
                minecraft.options.bobView().set(false);
                minecraft.options.particles().set(ParticleStatus.MINIMAL);
            });
            world.getServer().runOnServer(server -> {
                ServerPlayer player = FabricConnection.of(world).getServerPlayer();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
            });
            long lentBefore = shaders ? IrisLoans.lentDraws(context) : 0L;
            pairedDoors(context, client, world, reports);
            nether(context, client, world, reports);
            pocketDoor(context, client, world, PERSONAL, DoorItemIdentity.newPersonal(DoorForm.DOOR), "personal-door", reports);
            pocketDoor(context, client, world, PUBLIC, DoorItemIdentity.newPublic(DoorForm.DOOR), "public-door", reports);
            if (shaders) {
                long lent = IrisLoans.lentDraws(context) - lentBefore;
                LOGGER.info("WORMHOLES_FRAME_HITCH lentDraws={}", lent);
                assertTrue(lent > 0L, "cross-dimension views never drew with an idle dimension pipeline");
            }
        } finally {
            context.runOnClient(minecraft -> {
                if (ClientGui.hudHidden(minecraft) != hidden) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            context.restoreDefaultGameOptions();
        }
        for (FrameHitchTap.Report report : reports) {
            LOGGER.info(report.line(mode));
        }
        if (shaders) {
            for (FrameHitchTap.Report report : reports) {
                assertTrue(report.scenario().equals("paired-door") || report.creations() == 0,
                    report.scenario() + " constructed " + report.creations() + " shader pipelines after the first same-dimension view");
            }
        }
    }

    private static void nether(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world,
                               List<FrameHitchTap.Report> reports) {
        Vec3 front = new Vec3(NETHER.getX() + 1.0D, NETHER.getY(), NETHER.getZ() + 0.5D + ORBIT_BLOCKS);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            PortalViewFixture.netherFrame(server.overworld(), NETHER);
            player.teleportTo(server.overworld(), front.x, front.y, front.z, Set.of(), 180.0F, 0.0F, false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
        });
        settle(client, front);
        client.lookAt(180.0F, 0.0F);
        record(client, "nether-ignite", reports, () -> {
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
            awaitView(client, "minecraft:the_nether", "nether-ignite");
            client.waitTicks(VIEW_TICKS);
        });
        context.takeScreenshot("hitch-nether-front");
        Vec3 center = new Vec3(NETHER.getX() + 1.0D, NETHER.getY(), NETHER.getZ() + 0.5D);
        record(client, "nether-back", reports, () -> {
            orbit(client, center, ORBIT_BLOCKS, 0.0D, Math.PI);
            client.waitTicks(HOLD_TICKS);
        });
        context.takeScreenshot("hitch-nether-back");
        record(client, "nether-front-again", reports, () -> {
            orbit(client, center, ORBIT_BLOCKS, Math.PI, 0.0D);
            client.waitTicks(HOLD_TICKS);
        });
        record(client, "nether-cross", reports, () -> {
            PortalViewFixture.cross(client);
            client.waitTicks(CROSS_SETTLE_TICKS);
            float reverse = client.computeOnClient(minecraft -> minecraft.player.getYRot() + 180.0F);
            client.lookAt(reverse, 0.0F);
            client.waitTicks(CROSS_SETTLE_TICKS);
        });
        context.takeScreenshot("hitch-nether-return");
        record(client, "nether-return", reports, () -> {
            PortalViewFixture.cross(client);
            client.waitTicks(CROSS_SETTLE_TICKS);
        });
    }

    private static void orbit(FabricSeamlessClient client, Vec3 center, double radius, double from, double to) {
        for (int step = 1; step <= WALK_TICKS; step++) {
            double angle = from + (to - from) * step / WALK_TICKS;
            double x = center.x + Math.sin(angle) * radius;
            double z = center.z + Math.cos(angle) * radius;
            float yaw = (float) Math.toDegrees(Math.atan2(-(center.x - x), center.z - z));
            client.runOnClient(minecraft -> {
                minecraft.player.snapTo(x, center.y, z, yaw, 0.0F);
                minecraft.player.setDeltaMovement(Vec3.ZERO);
            });
            client.waitTicks(1);
        }
    }

    private static void pocketDoor(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world, BlockPos door,
                                   DoorItemIdentity identity, String scenario, List<FrameHitchTap.Report> reports) {
        place(client, world, door, MinecraftDoorItems.door(identity), identity);
        record(client, scenario, reports, () -> {
            open(context, door);
            awaitView(client, "wormholes:pockets", scenario);
            client.waitTicks(VIEW_TICKS);
        });
        context.takeScreenshot("hitch-" + scenario);
    }

    private static void pairedDoors(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world,
                                     List<FrameHitchTap.Report> reports) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftDoorItems.pairKit(DoorForm.DOOR));
            assertTrue(runtime(server).useItem(player, InteractionHand.MAIN_HAND), "paired door kit did not unpack");
        });
        int waited = 0;
        while (world.getServer().computeOnServer(server -> tagged(FabricConnection.of(world).getServerPlayer()).size()) != 2) {
            assertTrue(waited++ < TIMEOUT_TICKS, "paired door kit did not supply two door items");
            client.waitTicks(1);
        }
        List<ItemStack> items = world.getServer().computeOnServer(server -> tagged(FabricConnection.of(world).getServerPlayer()));
        place(client, world, PAIR_SECOND, items.get(1), MinecraftDoorItems.identity(items.get(1)).orElseThrow());
        place(client, world, PAIR_FIRST, items.getFirst(), MinecraftDoorItems.identity(items.getFirst()).orElseThrow());
        record(client, "paired-door", reports, () -> {
            open(context, PAIR_FIRST);
            awaitView(client, "minecraft:overworld", "paired-door");
            client.waitTicks(VIEW_TICKS);
        });
        context.takeScreenshot("hitch-paired-door");
        record(client, "paired-door-back", reports, () -> {
            orbit(client, new Vec3(PAIR_FIRST.getX() + 0.5D, PAIR_FIRST.getY(), PAIR_FIRST.getZ() + 0.5D), DOOR_ORBIT_BLOCKS, Math.PI, 0.0D);
            client.waitTicks(HOLD_TICKS);
        });
    }

    private static void place(FabricSeamlessClient client, TestSingleplayerContext world, BlockPos door, ItemStack item, DoorItemIdentity identity) {
        Vec3 start = new Vec3(door.getX() + 0.5D, door.getY(), door.getZ() - 2.5D);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            ServerLevel level = server.overworld();
            foundation(level, door);
            player.teleportTo(level, start.x, start.y, start.z, Set.of(), 0.0F, 0.0F, false);
            player.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
        });
        settle(client, start);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            BlockPos support = door.below();
            assertTrue(runtime(server).useBlock(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(support).add(0.0D, 0.5D, 0.0D), Direction.UP, support, false)), "door placement was not consumed");
        });
        int waited = 0;
        while (!world.getServer().computeOnServer(server -> runtime(server).doors().state().findEndpointByItem(identity.itemId()).isPresent()
            && server.overworld().getBlockState(door).getBlock() instanceof DoorBlock)) {
            assertTrue(waited++ < TIMEOUT_TICKS, "door was not placed at " + door);
            client.waitTicks(1);
        }
        world.getServer().runOnServer(server -> FabricConnection.of(world).getServerPlayer().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
        client.waitFor(minecraft -> minecraft.player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
            && minecraft.level.getBlockState(door).getBlock() instanceof DoorBlock, TIMEOUT_TICKS);
        client.lookAt(0.0F, 0.0F);
        client.waitTicks(20);
    }

    private static void open(ClientGameTestContext context, BlockPos door) {
        context.runOnClient(minecraft -> {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(door), Direction.NORTH, door, false);
            assertTrue(minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit).consumesAction(),
                "client did not interact with the dimensional door");
        });
    }

    private static void settle(FabricSeamlessClient client, Vec3 position) {
        client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(position) < 0.5D, TIMEOUT_TICKS);
        client.waitForChunksDownload();
        client.waitForChunksRender();
        client.waitTicks(40);
    }

    private static void record(FabricSeamlessClient client, String scenario, List<FrameHitchTap.Report> reports, Runnable workload) {
        client.runOnClient(minecraft -> FrameHitchTap.start());
        try {
            workload.run();
        } finally {
            FrameHitchTap.Report report = client.computeOnClient(minecraft -> FrameHitchTap.stop(scenario));
            reports.add(report);
            LOGGER.info(report.line("live"));
        }
    }

    private static void awaitView(FabricSeamlessClient client, String destination, String scenario) {
        int waited = 0;
        while (!client.computeOnClient(minecraft -> PortalViewFixture.drawsView(minecraft, destination))) {
            if (waited++ >= TIMEOUT_TICKS) {
                LOGGER.warn("WORMHOLES_FRAME_HITCH scenario={} view into {} never appeared", scenario, destination);
                return;
            }
            client.waitTicks(1);
        }
        LOGGER.info("WORMHOLES_FRAME_HITCH scenario={} view into {} appeared after {} ticks", scenario, destination, waited);
    }

    private static List<ItemStack> tagged(ServerPlayer player) {
        List<ItemStack> items = new ArrayList<>(2);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (MinecraftDoorItems.identity(item).isPresent()) {
                items.add(item.copy());
            }
        }
        return items;
    }

    private static void foundation(ServerLevel level, BlockPos door) {
        PortalViewFixture.fill(level, door.offset(-6, -1, -8), 13, 1, 17, Blocks.STONE.defaultBlockState());
        PortalViewFixture.fill(level, door.offset(-6, 0, -8), 13, 5, 17, Blocks.AIR.defaultBlockState());
    }

    private static final class IrisLoans {
        private static long lentDraws(ClientGameTestContext context) {
            return context.computeOnClient(minecraft -> IrisViewPipelines.diagnostics().lentDraws());
        }
    }
}
