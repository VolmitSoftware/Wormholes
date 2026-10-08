package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftStorePaths;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class SingleplayerStoreClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos MIN = new BlockPos(32, 100, 32);
    private static final int EDGE = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path gameStore = absolute(MinecraftStorePaths.configDirectory(context.computeOnClient(client -> client.gameDirectory.toPath())));
        ClientViewTestConfig.enableSharedStore(false);
        try {
            separateWorlds(context, gameStore);
            sharedWorlds(context, gameStore);
        } finally {
            ClientViewTestConfig.enableSharedStore(false);
        }
    }

    private static void separateWorlds(ClientGameTestContext context, Path gameStore) {
        TestWorldSave first;
        UUID portal;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            first = world.getWorldSave();
            assertStores(world, gameStore, false);
            portal = world.getServer().computeOnServer(SingleplayerStoreClientGameTest::create);
        }
        Path firstStore = store(first);
        assertTrue(Files.isRegularFile(PortalStateCodec.file(firstStore.resolve("portals"), portal)),
            "the portal was not saved inside the first world at " + firstStore);
        assertTrue(!Files.exists(PortalStateCodec.file(gameStore.resolve("portals"), portal)),
            "the first world's portal was saved in the game folder store " + gameStore);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            assertTrue(!store(world.getWorldSave()).equals(firstStore), "the second world reused the first world's save folder");
            assertStores(world, gameStore, false);
            assertTrue(!present(world, portal), "the first world's portal " + portal + " appeared in the second world");
        }
        try (TestSingleplayerContext world = first.open()) {
            assertStores(world, gameStore, false);
            assertTrue(present(world, portal), "the first world lost portal " + portal + " after the second world was opened");
        }
        LOGGER.info("WORMHOLES_SINGLEPLAYER_STORE_PASS separate portal {} stays in {}", portal, firstStore);
    }

    private static void sharedWorlds(ClientGameTestContext context, Path gameStore) {
        ClientViewTestConfig.enableSharedStore(true);
        UUID portal;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            assertStores(world, gameStore, true);
            portal = world.getServer().computeOnServer(SingleplayerStoreClientGameTest::create);
        }
        assertTrue(Files.isRegularFile(PortalStateCodec.file(gameStore.resolve("portals"), portal)),
            "the shared portal was not saved in the game folder store " + gameStore);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            assertStores(world, gameStore, true);
            assertTrue(present(world, portal), "the shared portal " + portal + " is missing from another world");
            world.getServer().runOnServer(server -> runtime(server).portals().remove(portal));
        }
        assertTrue(!Files.exists(PortalStateCodec.file(gameStore.resolve("portals"), portal)),
            "the shared portal was not removed from the game folder store");
        LOGGER.info("WORMHOLES_SINGLEPLAYER_STORE_PASS shared portal {} crossed worlds through {}", portal, gameStore);
    }

    private static UUID create(MinecraftServer server) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        MinecraftPortal existing = runtime.portals().at(level, MIN);
        if (existing != null) {
            runtime.portals().remove(existing.getId());
        }
        List<BlockPos> cells = new ArrayList<>(EDGE * EDGE);
        for (int x = 0; x < EDGE; x++) {
            for (int y = 0; y < EDGE; y++) {
                cells.add(MIN.offset(x, y, 0));
            }
        }
        return runtime.portals().create(null, level, cells, PortalType.PORTAL, new Vec3(0, 0, -1)).getId();
    }

    private static boolean present(TestSingleplayerContext world, UUID portal) {
        return world.getServer().computeOnServer(server -> runtime(server).portals().get(portal) != null);
    }

    private static void assertStores(TestSingleplayerContext world, Path gameStore, boolean shared) {
        Path save = store(world.getWorldSave());
        MinecraftStorePaths stores = world.getServer().computeOnServer(server -> runtime(server).stores());
        assertTrue(absolute(stores.config()).equals(gameStore), "settings are read from " + stores.config() + " instead of " + gameStore);
        assertTrue(absolute(stores.doors()).equals(save), "doors are stored at " + stores.doors() + " instead of " + save);
        Path data = shared ? gameStore : save;
        assertTrue(absolute(stores.data()).equals(data), "portals are stored at " + stores.data() + " instead of " + data);
    }

    private static Path store(TestWorldSave save) {
        return absolute(save.getSaveDirectory()).resolve("wormholes");
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
