package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

/** A level the client prepared for a portal before the view distance grew must take the current chunk radius once the player crosses into it. */
public final class CrossingChunkRadiusClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int EDGE = 3;
    private static final int TIMEOUT_TICKS = 1200;
    private static final BlockPos SOURCE = new BlockPos(0, 100, 0);
    private static final BlockPos DESTINATION = new BlockPos(160, 100, -40);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricConnection connection = FabricConnection.of(singleplayer);
            FabricSeamlessClient client = new FabricSeamlessClient(context, connection);
            FabricSeamlessServer server = new FabricSeamlessServer(singleplayer.getServer(), connection);
            SeamlessScenario.join(client);
            client.runOnClient(minecraft -> minecraft.options.renderDistance().set(8));
            client.waitFor(minecraft -> serverRadius(minecraft) == 8, TIMEOUT_TICKS);
            server.computeOnServer(minecraftServer -> {
                ServerLevel level = minecraftServer.overworld();
                ServerPlayer actor = connection.getServerPlayer();
                WormholesModRuntime wormholes = runtime(minecraftServer);
                for (BlockPos min : List.of(SOURCE, DESTINATION)) {
                    for (int x = -2; x <= 4; x++) {
                        for (int y = -1; y <= 3; y++) {
                            for (int z = -1; z <= 8; z++) {
                                level.setBlockAndUpdate(min.offset(x, y, z), y < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                }
                MinecraftPortal a = wormholes.portals().create(actor.getUUID(), level, cells(SOURCE), PortalType.WORMHOLE, new Vec3(0, 0, -1));
                MinecraftPortal b = wormholes.portals().create(actor.getUUID(), level, cells(DESTINATION), PortalType.WORMHOLE, new Vec3(0, 0, -1));
                SeamlessScenario.assertTrue(a != null && b != null, "portal creation rejected");
                SeamlessScenario.assertTrue(wormholes.portals().link(actor, a.getId(), b.getId()), "link rejected");
                SeamlessScenario.assertTrue(wormholes.portals().link(actor, b.getId(), a.getId()), "return link rejected");
                actor.setGameMode(GameType.CREATIVE);
                actor.getAbilities().mayfly = true;
                actor.getAbilities().flying = true;
                actor.onUpdateAbilities();
                return actor.teleportTo(level, SOURCE.getX() + 1.5D, SOURCE.getY() + 0.2D, SOURCE.getZ() + 6.5D, Set.of(), 180.0F, 0.0F, false);
            });
            client.waitTicks(200);
            client.runOnClient(minecraft -> minecraft.options.renderDistance().set(16));
            client.waitFor(minecraft -> serverRadius(minecraft) == 16, TIMEOUT_TICKS);
            client.waitTicks(100);
            int departure = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.level));
            client.holdForward();
            client.waitFor(minecraft -> minecraft.player.getZ() < DESTINATION.getZ() - 1.0D || minecraft.player.getZ() < SOURCE.getZ() - 1.0D && minecraft.player.getX() > 100.0D, TIMEOUT_TICKS);
            client.releaseForward();
            client.waitTicks(20);
            String report = client.computeOnClient(minecraft -> "level changed " + (System.identityHashCode(minecraft.level) != departure)
                + ", player x " + minecraft.player.getX() + ", server radius " + serverRadius(minecraft) + ", cache radius " + cacheRadius(minecraft));
            LOGGER.info("[crossing-chunk-radius] {}", report);
            int[] radii = client.computeOnClient(minecraft -> new int[] {serverRadius(minecraft), cacheRadius(minecraft)});
            SeamlessScenario.assertTrue(radii[1] >= radii[0], "the arrival level keeps a smaller chunk window than the server sends: " + report);
        }
    }

    private static int serverRadius(Minecraft minecraft) {
        return ((PreparedPacketAccess) minecraft.getConnection()).wormholes$chunkRadius();
    }

    private static int cacheRadius(Minecraft minecraft) {
        try {
            Field storageField = ClientChunkCache.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            Object storage = storageField.get(minecraft.level.getChunkSource());
            Field radius = storage.getClass().getDeclaredField("chunkRadius");
            radius.setAccessible(true);
            return radius.getInt(storage);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(EDGE * EDGE);
        for (int x = 0; x < EDGE; x++) {
            for (int y = 0; y < EDGE; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        return cells;
    }
}
