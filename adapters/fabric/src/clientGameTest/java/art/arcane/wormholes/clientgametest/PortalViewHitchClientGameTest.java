package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.modded.MinecraftDoorItems;
import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
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
    private static final BlockPos MIRROR = new BlockPos(1600, 90, 0);
    private static final BlockPos NESTED_FIRST = new BlockPos(2000, 90, 0);
    private static final BlockPos NESTED_SECOND = new BlockPos(2000, 90, 12);
    private static final BlockPos SECOND_LEFT = new BlockPos(2400, 90, 0);
    private static final BlockPos SECOND_RIGHT = new BlockPos(2403, 90, 0);
    private static final String POCKETS = "wormholes:pockets";
    private static final Set<String> LENDING = Set.of("nether-ignite", "nether-back", "nether-front-again", "nether-cross", "nether-return",
        "personal-door", "public-door");

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean shaders = FabricLoader.getInstance().isModLoaded("iris");
        String mode = shaders ? "shaders" : "plain";
        boolean hidden = context.computeOnClient(minecraft -> ClientGui.hudHidden(minecraft));
        ClientViewTestConfig.enableSeamless(true);
        List<Measured> reports = new ArrayList<>(12);
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
            pairedDoors(context, client, world, reports);
            nether(context, client, world, reports);
            pocketDoor(context, client, world, PERSONAL, DoorItemIdentity.newPersonal(DoorForm.DOOR), "personal-door", reports);
            pocketDoor(context, client, world, PUBLIC, DoorItemIdentity.newPublic(DoorForm.DOOR), "public-door", reports);
            mirror(context, client, world, reports);
            nested(context, client, world, reports);
            secondView(context, client, world, reports);
        } finally {
            context.runOnClient(minecraft -> {
                if (ClientGui.hudHidden(minecraft) != hidden) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            context.restoreDefaultGameOptions();
        }
        for (Measured measured : reports) {
            LOGGER.info(measured.report().line(mode));
        }
        if (shaders) {
            List<String> failures = new ArrayList<>(reports.size() * 4);
            for (Measured measured : reports) {
                failures.addAll(acceptance(measured));
            }
            for (String failure : failures) {
                LOGGER.warn("WORMHOLES_FRAME_HITCH_FAIL {}", failure);
            }
            assertTrue(failures.isEmpty(), "portal view frames exceeded the pipeline construction budget: " + String.join("; ", failures));
        }
    }

    private static List<String> acceptance(Measured measured) {
        FrameHitchTap.Report report = measured.report();
        List<String> failures = new ArrayList<>(5);
        if (report.frameCreations() > 1) {
            failures.add(String.format(Locale.ROOT, "%s constructed %d Iris pipelines in one frame", report.scenario(), report.frameCreations()));
        }
        if (report.linkedCreationFrames() > 0) {
            failures.add(String.format(Locale.ROOT, "%s linked GL programs in %d frames that constructed an Iris pipeline", report.scenario(),
                report.linkedCreationFrames()));
        }
        if (report.frameLinks() > 1) {
            failures.add(String.format(Locale.ROOT, "%s linked %d GL programs in one frame", report.scenario(), report.frameLinks()));
        }
        if (LENDING.contains(report.scenario()) && report.creations() > 0) {
            failures.add(String.format(Locale.ROOT, "%s constructed %d Iris pipelines instead of borrowing idle dimension pipelines",
                report.scenario(), report.creations()));
        }
        if (LENDING.contains(report.scenario()) && measured.lentDraws() == 0L) {
            failures.add(String.format(Locale.ROOT, "%s never drew a view with an idle dimension pipeline", report.scenario()));
        }
        return failures;
    }

    private static void nether(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world,
                               List<Measured> reports) {
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
            awaitView(client, "nether-ignite", "minecraft:the_nether", minecraft -> drawsViews(minecraft, "minecraft:the_nether", 1));
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
                                   DoorItemIdentity identity, String scenario, List<Measured> reports) {
        place(client, world, door, MinecraftDoorItems.door(identity), identity);
        record(client, scenario, reports, () -> {
            open(context, door);
            awaitView(client, scenario, POCKETS, minecraft -> drawsViews(minecraft, POCKETS, 1));
            client.waitTicks(VIEW_TICKS);
        });
        context.takeScreenshot("hitch-" + scenario);
    }

    private static void pairedDoors(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world,
                                     List<Measured> reports) {
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
            awaitView(client, "paired-door", "minecraft:overworld", minecraft -> drawsViews(minecraft, "minecraft:overworld", 1));
            client.waitTicks(VIEW_TICKS);
        });
        cameras(client, "paired-door");
        context.takeScreenshot("hitch-paired-door");
        record(client, "paired-door-back", reports, () -> {
            orbit(client, new Vec3(PAIR_FIRST.getX() + 0.5D, PAIR_FIRST.getY(), PAIR_FIRST.getZ() + 0.5D), DOOR_ORBIT_BLOCKS, Math.PI, 0.0D);
            client.waitTicks(HOLD_TICKS);
        });
    }

    private static void mirror(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world,
                               List<Measured> reports) {
        Vec3 front = new Vec3(MIRROR.getX() + 1.5D, MIRROR.getY(), MIRROR.getZ() + 6.5D);
        UUID mirror = world.getServer().computeOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            ServerLevel level = server.overworld();
            PortalViewFixture.fill(level, MIRROR.offset(-6, -1, -4), 15, 1, 17, Blocks.STONE.defaultBlockState());
            PortalViewFixture.fill(level, MIRROR.offset(-6, 0, -4), 15, 6, 17, Blocks.AIR.defaultBlockState());
            PortalViewFixture.fill(level, MIRROR.offset(-1, 0, -1), 5, 4, 1, MinecraftDyedItems.concreteBlock(DyeColor.YELLOW).defaultBlockState());
            WormholesModRuntime runtime = runtime(server);
            MinecraftPortal portal = runtime.portals().create(player.getUUID(), level, cells(MIRROR), PortalType.PORTAL, new Vec3(0, 0, -1));
            portal.setMirrorMode(true);
            portal.setProjectionMode(ProjectionMode.OFF);
            runtime.portals().save(portal);
            player.teleportTo(level, front.x, front.y, front.z, Set.of(), 180.0F, 0.0F, false);
            return portal.getId();
        });
        settle(client, front);
        client.lookAt(180.0F, 0.0F);
        record(client, "mirror", reports, () -> {
            world.getServer().runOnServer(server -> {
                WormholesModRuntime runtime = runtime(server);
                MinecraftPortal portal = runtime.portals().get(mirror);
                portal.setProjectionMode(ProjectionMode.ON);
                runtime.portals().save(portal);
            });
            assertTrue(awaitView(client, "mirror", "a mirror", PortalViewHitchClientGameTest::drawsMirror), "the mirror view never appeared");
            client.waitTicks(VIEW_TICKS);
        });
        cameras(client, "mirror");
        context.takeScreenshot("hitch-mirror");
    }

    private static void nested(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world,
                               List<Measured> reports) {
        Vec3 between = new Vec3(NESTED_FIRST.getX() + 1.5D, NESTED_FIRST.getY(), NESTED_FIRST.getZ() + 6.0D);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            ServerLevel level = server.overworld();
            hallway(level);
            player.teleportTo(level, between.x, between.y, between.z, Set.of(), 180.0F, 0.0F, false);
        });
        settle(client, between);
        client.lookAt(180.0F, 0.0F);
        record(client, "nested", reports, () -> {
            world.getServer().runOnServer(server -> {
                ServerPlayer player = FabricConnection.of(world).getServerPlayer();
                ServerLevel level = server.overworld();
                WormholesModRuntime runtime = runtime(server);
                MinecraftPortal first = runtime.portals().create(player.getUUID(), level, cells(NESTED_FIRST), PortalType.PORTAL, new Vec3(0, 0, 1));
                MinecraftPortal second = runtime.portals().create(player.getUUID(), level, cells(NESTED_SECOND), PortalType.PORTAL, new Vec3(0, 0, 1));
                runtime.nexus().pair(player, first, second, true);
                assertTrue(second.getId().equals(first.getDestinationId()) && first.getId().equals(second.getDestinationId()),
                    "nested hallway portals are not reciprocal");
            });
            assertTrue(awaitView(client, "nested", "a nested same-dimension view", minecraft -> FrameHitchTap.deepestView() >= 2),
                "the hallway portals never drew a nested view");
            client.waitTicks(VIEW_TICKS);
        });
        cameras(client, "nested");
        context.takeScreenshot("hitch-nested");
    }

    private static void secondView(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world,
                                   List<Measured> reports) {
        DoorItemIdentity left = DoorItemIdentity.newPublic(DoorForm.DOOR);
        DoorItemIdentity right = DoorItemIdentity.newPublic(DoorForm.DOOR);
        world.getServer().runOnServer(server -> foundation(server.overworld(), SECOND_LEFT));
        install(client, world, SECOND_LEFT, MinecraftDoorItems.door(left), left);
        install(client, world, SECOND_RIGHT, MinecraftDoorItems.door(right), right);
        Vec3 between = new Vec3(SECOND_LEFT.getX() + 2.0D, SECOND_LEFT.getY(), SECOND_LEFT.getZ() - 3.5D);
        world.getServer().runOnServer(server -> FabricConnection.of(world).getServerPlayer()
            .teleportTo(server.overworld(), between.x, between.y, between.z, Set.of(), 0.0F, 0.0F, false));
        settle(client, between);
        client.lookAt(0.0F, 0.0F);
        open(context, SECOND_LEFT);
        assertTrue(awaitView(client, "second-view", POCKETS, minecraft -> drawsViews(minecraft, POCKETS, 1)),
            "the first pocket door view never appeared");
        client.waitTicks(VIEW_TICKS / 2);
        record(client, "second-view", reports, () -> {
            open(context, SECOND_RIGHT);
            assertTrue(awaitView(client, "second-view", "two views into " + POCKETS, minecraft -> drawsViews(minecraft, POCKETS, 2)),
                "the second pocket door view never appeared beside the first");
            client.waitTicks(VIEW_TICKS);
        });
        cameras(client, "second-view");
        context.takeScreenshot("hitch-second-view");
    }

    private static void place(FabricSeamlessClient client, TestSingleplayerContext world, BlockPos door, ItemStack item, DoorItemIdentity identity) {
        world.getServer().runOnServer(server -> foundation(server.overworld(), door));
        install(client, world, door, item, identity);
    }

    private static void install(FabricSeamlessClient client, TestSingleplayerContext world, BlockPos door, ItemStack item, DoorItemIdentity identity) {
        Vec3 start = new Vec3(door.getX() + 0.5D, door.getY(), door.getZ() - 2.5D);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = FabricConnection.of(world).getServerPlayer();
            ServerLevel level = server.overworld();
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

    private static void record(FabricSeamlessClient client, String scenario, List<Measured> reports, Runnable workload) {
        boolean shaders = FabricLoader.getInstance().isModLoaded("iris");
        IrisCounters before = shaders ? IrisDiagnostics.counters(client) : null;
        client.runOnClient(minecraft -> FrameHitchTap.start());
        try {
            workload.run();
        } finally {
            FrameHitchTap.Report report = client.computeOnClient(minecraft -> FrameHitchTap.stop(scenario));
            LOGGER.info(report.line("live"));
            LOGGER.info(report.linkLine("live"));
            for (String line : report.buildLines("live")) {
                LOGGER.info(line);
            }
            long lentDraws = 0L;
            if (before != null) {
                IrisCounters after = IrisDiagnostics.counters(client);
                lentDraws = after.lentDraws() - before.lentDraws();
                LOGGER.info(after.since(before, scenario));
            }
            reports.add(new Measured(report, lentDraws));
        }
    }

    private static void cameras(FabricSeamlessClient client, String scenario) {
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            for (String line : IrisDiagnostics.cameras(client, scenario)) {
                LOGGER.info(line);
            }
        }
    }

    private static boolean awaitView(FabricSeamlessClient client, String scenario, String target, Predicate<Minecraft> drawn) {
        int waited = 0;
        while (!client.computeOnClient(drawn::test)) {
            if (waited++ >= TIMEOUT_TICKS) {
                LOGGER.warn("WORMHOLES_FRAME_HITCH scenario={} view into {} never appeared", scenario, target);
                return false;
            }
            client.waitTicks(1);
        }
        LOGGER.info("WORMHOLES_FRAME_HITCH scenario={} view into {} appeared after {} ticks", scenario, target, waited);
        return true;
    }

    private static boolean drawsViews(Minecraft minecraft, String destination, int required) {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null || minecraft.level == null) {
            return false;
        }
        int found = 0;
        for (PortalView view : wormholes.portalViews().current()) {
            if (view.source() == minecraft.level && view.kind() == PortalView.Kind.ARM
                && view.destination().dimension().identifier().toString().equals(destination)) {
                found++;
            }
        }
        return found >= required;
    }

    private static boolean drawsMirror(Minecraft minecraft) {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null || minecraft.level == null) {
            return false;
        }
        for (PortalView view : wormholes.portalViews().current()) {
            if (view.source() == minecraft.level && view.kind() == PortalView.Kind.MIRROR) {
                return true;
            }
        }
        return false;
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

    private static void hallway(ServerLevel level) {
        PortalViewFixture.fill(level, NESTED_FIRST.offset(-5, -1, -7), 14, 1, 27, Blocks.SEA_LANTERN.defaultBlockState());
        PortalViewFixture.fill(level, NESTED_FIRST.offset(-5, 0, -7), 14, 6, 27, Blocks.AIR.defaultBlockState());
        int length = NESTED_SECOND.getZ() - NESTED_FIRST.getZ() + 1;
        PortalViewFixture.fill(level, NESTED_FIRST.offset(-1, 0, 0), 1, 4, length, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        PortalViewFixture.fill(level, NESTED_FIRST.offset(3, 0, 0), 1, 4, length, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        PortalViewFixture.fill(level, NESTED_FIRST.offset(0, 3, 0), 3, 1, length, Blocks.SEA_LANTERN.defaultBlockState());
    }

    private record Measured(FrameHitchTap.Report report, long lentDraws) {
    }

    private record IrisCounters(long preparations, long drawMisses, long drawCreations, long adoptions, long lentDraws, long hits) {
        private String since(IrisCounters before, String scenario) {
            return String.format(Locale.ROOT,
                "WORMHOLES_FRAME_HITCH_IRIS scenario=%s preparations=%d drawMisses=%d drawCreations=%d adoptions=%d lentDraws=%d hits=%d",
                scenario, preparations - before.preparations, drawMisses - before.drawMisses, drawCreations - before.drawCreations,
                adoptions - before.adoptions, lentDraws - before.lentDraws, hits - before.hits);
        }
    }

    private static final class IrisDiagnostics {
        private static IrisCounters counters(FabricSeamlessClient client) {
            return client.computeOnClient(minecraft -> {
                IrisViewPipelines.Diagnostics diagnostics = IrisViewPipelines.diagnostics();
                return new IrisCounters(diagnostics.preparations(), diagnostics.drawMisses(), diagnostics.drawCreations(), diagnostics.adoptions(),
                    diagnostics.lentDraws(), diagnostics.hits());
            });
        }

        private static List<String> cameras(FabricSeamlessClient client, String scenario) {
            return client.computeOnClient(minecraft -> {
                IrisViewPipelines.CameraSnapshots snapshots = IrisViewPipelines.cameraSnapshots();
                List<String> lines = new ArrayList<>(snapshots.cameras().size());
                for (IrisViewPipelines.CameraSnapshot camera : snapshots.cameras()) {
                    List<String> traversal = new ArrayList<>(camera.traversal().size());
                    for (IrisViewPipelines.CameraIdentity identity : camera.traversal()) {
                        traversal.add(identity.kind() + "(" + identity.source() + ">" + identity.destination() + ")");
                    }
                    lines.add(String.format(Locale.ROOT, "WORMHOLES_FRAME_HITCH_CAMERA scenario=%s dimension=%s depth=%d roles=%s cameras=%s cached=%s path=%s",
                        scenario, camera.dimension(), camera.traversal().size(), camera.roles(), traversal,
                        camera.cached() == null ? "-" : Long.toString(camera.cached().identity()), camera.path()));
                }
                return lines;
            });
        }
    }
}
