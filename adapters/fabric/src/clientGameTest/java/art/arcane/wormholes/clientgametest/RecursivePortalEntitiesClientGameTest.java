package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class RecursivePortalEntitiesClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos FIRST = new BlockPos(32, 100, 32);
    private static final BlockPos SECOND = new BlockPos(32, 100, 44);
    private static final Vec3 POSITION = new Vec3(33.5D, 100.0D, 38.0D);
    private static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            SeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            ClientOptions options = context.computeOnClient(minecraft -> new ClientOptions(minecraft.options.getCameraType(),
                minecraft.gui.hud.isHidden(), WormholesClient.instance().config().clientRecursion));
            List<UUID> portals = new ArrayList<>(2);
            Scene scene = world.getServer().computeOnServer(server -> build(server, world.getConnection().getServerPlayer(), portals));
            try {
                context.runOnClient(minecraft -> {
                    assertHidden(minecraft);
                    minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                    WormholesClient.instance().config().clientRecursion = true;
                    if (!minecraft.gui.hud.isHidden()) {
                        minecraft.gui.hud.toggle();
                    }
                });
                context.waitFor(minecraft -> minecraft.player.position().distanceTo(POSITION) < 0.05D
                    && minecraft.level.getEntity(scene.mobId()) != null, TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 0.0F);
                world.getConnection().waitForChunksRender();
                context.waitFor(minecraft -> armed(scene.portals()), TIMEOUT_TICKS);
                context.runOnClient(minecraft -> PortalEntityRenderTap.start());
                context.waitTicks(SETTLE_TICKS);
                context.takeScreenshot("recursive-hallway-initial");
                List<PortalEntityRenderTap.Sample> initial = context.computeOnClient(minecraft -> PortalEntityRenderTap.samples());
                LOGGER.info("Recursive hallway initial samples {}", initial);
                context.waitFor(minecraft -> PortalEntityRenderTap.failure() != null || nestedMob(scene.mob()) != null, TIMEOUT_TICKS);
                PortalEntityRenderTap.Sample nested = context.computeOnClient(minecraft -> nestedMob(scene.mob()));
                context.runOnClient(minecraft -> {
                    assertTrue(PortalEntityRenderTap.failure() == null, PortalEntityRenderTap.failure());
                    assertTrue(nested != null, "the marked mob was not extracted and submitted at recursive depth 2 or greater: " + PortalEntityRenderTap.samples());
                });
                int depth = nested.depth();
                LOGGER.info("Recursive hallway mob extracted and submitted at depth {} in frame {}", depth, nested.frame());
                LOGGER.info("Recursive hallway sample {}", nested);
                context.takeScreenshot("recursive-hallway-before-crops");
                Crop mobCrop = crop(nested, scene.mob());
                PortalEntityRenderTap.Sample both = context.computeOnClient(minecraft -> atPath(nested.path()));
                assertTrue(both != null && visible(both, scene.player()),
                    "the first-person local player was not extracted and submitted in the recursive hallway: " + both);
                Crop playerCrop = crop(both, scene.player());
                context.takeScreenshot("recursive-hallway-player-and-mob");
                int[] mobVisible = capture(context, mobCrop);
                int[] playerVisible = capture(context, playerCrop);
                context.waitTicks(SETTLE_TICKS);
                double mobNoise = difference(mobVisible, capture(context, mobCrop));
                double playerNoise = difference(playerVisible, capture(context, playerCrop));
                world.getServer().runOnServer(server -> Objects.requireNonNull(server.overworld().getEntity(scene.mob())).setInvisible(true));
                context.waitFor(minecraft -> minecraft.level.getEntity(scene.mobId()).isInvisible(), TIMEOUT_TICKS);
                context.waitTicks(SETTLE_TICKS);
                context.takeScreenshot("recursive-hallway-mob-hidden");
                double mobPixels = difference(mobVisible, capture(context, mobCrop));
                assertTrue(mobPixels > Math.max(1.0D, mobNoise * 3.0D + 0.5D),
                    "the submitted recursive mob produced no visible GPU body: pixel difference " + mobPixels + ", idle noise " + mobNoise);
                int[] withoutMob = capture(context, playerCrop);
                world.getServer().runOnServer(server -> world.getConnection().getServerPlayer()
                    .addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 600, 0, true, false)));
                context.waitFor(minecraft -> minecraft.player.isInvisible(), TIMEOUT_TICKS);
                context.waitTicks(SETTLE_TICKS);
                context.takeScreenshot("recursive-hallway-player-hidden");
                double playerPixels = difference(withoutMob, capture(context, playerCrop));
                assertTrue(playerPixels > Math.max(1.0D, playerNoise * 3.0D + 0.5D),
                    "the submitted recursive local player produced no visible GPU body: pixel difference " + playerPixels + ", idle noise " + playerNoise);
                context.runOnClient(RecursivePortalEntitiesClientGameTest::assertHidden);
                LOGGER.info("WORMHOLES_RECURSIVE_ENTITIES_PASS depth {} mob_pixels {} player_pixels {} {}", depth, mobPixels, playerPixels,
                    context.computeOnClient(minecraft -> PortalStencilRenderer.instance().debugLine()));
            } finally {
                context.runOnClient(minecraft -> {
                    PortalEntityRenderTap.stop();
                    minecraft.options.setCameraType(options.camera());
                    WormholesClient.instance().config().clientRecursion = options.recursion();
                    if (minecraft.gui.hud.isHidden() != options.hidden()) {
                        minecraft.gui.hud.toggle();
                    }
                });
                world.getServer().runOnServer(server -> {
                    ServerPlayer player = world.getConnection().getServerPlayer();
                    player.removeEffect(MobEffects.INVISIBILITY);
                    server.overworld().getEntity(scene.mob()).discard();
                    for (UUID portal : portals) {
                        runtime(server).portals().remove(player, portal);
                    }
                });
            }
        }
    }

    private static Scene build(MinecraftServer server, ServerPlayer player, List<UUID> portals) {
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        for (int x = 27; x <= 40; x++) {
            for (int z = 25; z <= 51; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.SEA_LANTERN.defaultBlockState());
                for (int y = 100; y <= 105; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int z = FIRST.getZ(); z <= SECOND.getZ(); z++) {
            for (int y = 100; y <= 103; y++) {
                level.setBlockAndUpdate(new BlockPos(31, y, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(35, y, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            }
            for (int x = 32; x <= 34; x++) {
                level.setBlockAndUpdate(new BlockPos(x, 103, z), Blocks.SEA_LANTERN.defaultBlockState());
            }
        }
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal first = runtime.portals().create(player.getUUID(), level, cells(FIRST), PortalType.PORTAL, new Vec3(0, 0, 1));
        portals.add(first.getId());
        MinecraftPortal second = runtime.portals().create(player.getUUID(), level, cells(SECOND), PortalType.PORTAL, new Vec3(0, 0, 1));
        portals.add(second.getId());
        runtime.nexus().pair(player, first, second, true);
        assertTrue(second.getId().equals(first.getDestinationId()) && first.getId().equals(second.getDestinationId()), "hallway portals are not reciprocal");
        Sheep mob = Objects.requireNonNull(EntityTypes.SHEEP.create(level, EntitySpawnReason.COMMAND));
        mob.setColor(DyeColor.MAGENTA);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.snapTo(32.65D, 100.0D, 37.0D, 180.0F, 0.0F);
        assertTrue(level.addFreshEntity(mob), "marked hallway mob could not be added");
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        player.teleportTo(level, POSITION.x, POSITION.y, POSITION.z, Set.of(), 180.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
        return new Scene(List.copyOf(portals), player.getUUID(), mob.getUUID(), mob.getId());
    }

    private static boolean armed(List<UUID> portals) {
        return WormholesClient.instance().seamlessTravel().armed(portals.getFirst())
            && WormholesClient.instance().seamlessTravel().armed(portals.get(1));
    }

    private static PortalEntityRenderTap.Sample nestedMob(UUID mob) {
        PortalEntityRenderTap.Sample selected = null;
        Vec3 physicalEye = Minecraft.getInstance().player.getEyePosition();
        for (PortalEntityRenderTap.Sample sample : PortalEntityRenderTap.samples()) {
            if (sample.depth() >= 2 && visible(sample, mob) && sample.eye().distanceTo(physicalEye) > 2.0D
                && sample.screen().contains(sample.entities().get(mob).center())
                && (selected == null || sample.depth() < selected.depth())) {
                selected = sample;
            }
        }
        return selected;
    }

    private static PortalEntityRenderTap.Sample atPath(List<Object> path) {
        for (PortalEntityRenderTap.Sample sample : PortalEntityRenderTap.samples()) {
            if (sample.path().equals(path)) {
                return sample;
            }
        }
        return null;
    }

    private static boolean visible(PortalEntityRenderTap.Sample sample, UUID entity) {
        PortalEntityRenderTap.EntitySample state = sample.entities().get(entity);
        return state != null && state.extracted() && state.submitted();
    }

    private static Crop crop(PortalEntityRenderTap.Sample sample, UUID identity) {
        PortalEntityRenderTap.EntitySample entity = sample.entities().get(identity);
        PortalEntityRenderTap.ScreenCamera camera = sample.screen();
        int[] top = camera.point(entity.top());
        int[] bottom = camera.point(entity.bottom());
        int[] left = camera.point(entity.left());
        int[] right = camera.point(entity.right());
        assertTrue(top != null && bottom != null && left != null && right != null,
            "recursive entity crop is behind the render-time camera: " + sample);
        int x = Math.max(0, Math.min(left[0], right[0]) - 2);
        int y = Math.max(0, Math.min(top[1], bottom[1]) - 2);
        int maxX = Math.min(camera.width(), Math.max(left[0], right[0]) + 3);
        int maxY = Math.min(camera.height(), Math.max(top[1], bottom[1]) + 3);
        assertTrue(maxX > x && maxY > y, "recursive entity crop is outside the render-time framebuffer: " + sample);
        return new Crop(x, y, maxX - x, maxY - y);
    }

    private static int[] capture(ClientGameTestContext context, Crop crop) {
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        context.runOnClient(client -> FramebufferProbe.capture(client, captured::set));
        context.waitFor(client -> captured.get() != null, TIMEOUT_TICKS);
        FramebufferProbe image = captured.get();
        assertTrue(crop.x() >= 0 && crop.y() >= 0 && crop.x() + crop.width() <= image.width()
            && crop.y() + crop.height() <= image.height(), "recursive entity crop extends beyond the framebuffer");
        int[] pixels = new int[crop.width() * crop.height()];
        for (int y = 0; y < crop.height(); y++) {
            for (int x = 0; x < crop.width(); x++) {
                pixels[y * crop.width() + x] = image.read(crop.x() + x, crop.y() + y);
            }
        }
        return pixels;
    }

    private static double difference(int[] first, int[] second) {
        long distance = 0L;
        for (int index = 0; index < first.length; index++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                distance += Math.abs(((first[index] >> shift) & 255) - ((second[index] >> shift) & 255));
            }
        }
        return distance / (first.length * 3.0D);
    }

    private static void assertHidden(Minecraft client) {
        assertTrue(NativeTestWindow.hiddenAndUnfocused(client),
            "recursive entity acceptance requires a hidden, unfocused native GPU window");
        assertTrue(NativeTestWindow.mouseReleased(client), "recursive entity acceptance grabbed the desktop mouse");
        assertTrue(client.gameRenderer.mainRenderTarget().width >= 640 && client.gameRenderer.mainRenderTarget().height >= 360,
            "recursive entity acceptance requires a native GPU framebuffer");
    }

    private record Scene(List<UUID> portals, UUID player, UUID mob, int mobId) {
    }

    private record Crop(int x, int y, int width, int height) {
    }

    private record ClientOptions(CameraType camera, boolean hidden, boolean recursion) {
    }
}
