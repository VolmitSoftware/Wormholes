package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public final class GravityScaleDemoClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        Set<String> selected = Set.of(System.getProperty("wormholes.demo.takes",
            "gravity-pov,gravity-third-person,scale-pov,scale-third-person").split(","));
        if (!Set.of("gravity-pov", "gravity-third-person", "scale-pov", "scale-third-person").containsAll(selected)) {
            throw new IllegalArgumentException("Unknown native demo take");
        }
        for (CameraType camera : List.of(CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK)) {
            String id = camera.isFirstPerson() ? "gravity-pov" : "gravity-third-person";
            if (!selected.contains(id)) {
                continue;
            }
            try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
                GravityPortalClientGameTest.demo(new FabricSeamlessClient(context, singleplayer.getConnection()),
                    new Server(singleplayer.getServer(), singleplayer.getConnection()), controls -> flight(context, controls), camera, id);
            }
        }
        for (CameraType camera : List.of(CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK)) {
            String id = camera.isFirstPerson() ? "scale-pov" : "scale-third-person";
            if (!selected.contains(id)) {
                continue;
            }
            try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
                ScaledPairClientGameTest.demo(context, singleplayer.getConnection(), singleplayer.getServer(), camera, id);
            }
        }
    }

    private static void flight(ClientGameTestContext context, GravityPortalClientGameTest.FlightControls controls) {
        if (controls.jump()) {
            context.getInput().holdKey(options -> options.keyJump);
        } else {
            context.getInput().releaseKey(options -> options.keyJump);
        }
        if (controls.descend()) {
            context.getInput().holdKey(options -> options.keyShift);
        } else {
            context.getInput().releaseKey(options -> options.keyShift);
        }
    }

    private record Server(TestServerContext context, TestServerConnection connection) implements GravityPortalClientGameTest.Server {
        @Override
        public void run(Consumer<MinecraftServer> action) {
            context.runOnServer(action::accept);
        }

        @Override
        public <T> T compute(Function<MinecraftServer, T> action) {
            return context.computeOnServer(action::apply);
        }

        @Override
        public ServerPlayer player() {
            return connection.getServerPlayer();
        }
    }
}
