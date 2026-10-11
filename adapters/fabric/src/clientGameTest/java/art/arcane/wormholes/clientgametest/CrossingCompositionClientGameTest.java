package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.Objects;

public final class CrossingCompositionClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, singleplayer.getConnection());
            FabricSeamlessServer server = new FabricSeamlessServer(singleplayer.getServer(), singleplayer.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            cross(context, singleplayer, client, server, new Scene("far", Level.OVERWORLD, 0, 420));
            cross(context, singleplayer, client, server, new Scene("near", Level.OVERWORLD, 48, 44));
            cross(context, singleplayer, client, server, new Scene("dimension", Level.END, 96, 420));
        } finally {
            context.runOnClient(minecraft -> CrossingCompositionTap.close());
        }
    }

    private static void cross(ClientGameTestContext context, TestSingleplayerContext singleplayer, FabricSeamlessClient client,
                              FabricSeamlessServer server, Scene scene) {
        BlockPos source = new BlockPos(scene.x(), 70, 20);
        BlockPos destination = new BlockPos(scene.x(), 70, scene.z());
        SeamlessScenario.Route route = server.build(new SeamlessScenario.RouteSpec(Level.OVERWORLD, source,
            scene.dimension(), destination, OrientationPolicy.FRAME, false));
        singleplayer.getServer().runOnServer(minecraftServer -> {
            illuminate(Objects.requireNonNull(minecraftServer.getLevel(Level.OVERWORLD)), source);
            illuminate(Objects.requireNonNull(minecraftServer.getLevel(scene.dimension())), destination);
        });
        server.approach(route);
        SeamlessScenario.awaitPrepared(client);
        context.takeScreenshot("composition-" + scene.name() + "-approach");
        context.runOnClient(minecraft -> CrossingCompositionTap.arm(minecraft, scene.name() + "-out", source.getZ() + 0.5D));
        try {
            SeamlessScenario.Crossing crossing = SeamlessScenario.walkThrough(client, "composition-" + scene.name());
            SeamlessScenario.assertSeamlessTravel(client, route, crossing, SeamlessScenario.RETURN_VIEW_TICKS);
            client.waitFor(minecraft -> CrossingCompositionTap.complete(), 200);
            CrossingCompositionTap.verify();
        } finally {
            context.runOnClient(minecraft -> CrossingCompositionTap.close());
        }
        SeamlessScenario.turnBack(client, route);
        context.runOnClient(minecraft -> CrossingCompositionTap.arm(minecraft, scene.name() + "-return", destination.getZ() + 0.5D));
        try {
            SeamlessScenario.Crossing crossing = SeamlessScenario.walkThrough(client, "composition-" + scene.name() + "-return");
            SeamlessScenario.assertSeamlessReturn(client, route, crossing, SeamlessScenario.RETURN_VIEW_TICKS);
            client.waitFor(minecraft -> CrossingCompositionTap.complete(), 200);
            CrossingCompositionTap.verify();
        } finally {
            context.runOnClient(minecraft -> CrossingCompositionTap.close());
            SeamlessScenario.finish(client, server, route);
        }
    }

    private static void illuminate(ServerLevel level, BlockPos minimum) {
        for (int x = -6; x <= 8; x++) {
            for (int y = 0; y <= 5; y++) {
                level.setBlockAndUpdate(minimum.offset(x, y, -12), Blocks.SEA_LANTERN.defaultBlockState());
                level.setBlockAndUpdate(minimum.offset(x, y, 9), Blocks.SEA_LANTERN.defaultBlockState());
            }
        }
    }

    private record Scene(String name, ResourceKey<Level> dimension, int x, int z) {
    }
}
