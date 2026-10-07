package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.PreparedTravelPlayerMixin;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelNoSuppressionTest extends MinecraftTestBase {
    @Test
    public void seamlessPredictionKeepsSendingMovementWhilePreparedPredictionSuppressesIt() throws ReflectiveOperationException {
        for (boolean seamless : new boolean[]{true, false}) {
            ClientLevel source = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
            try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(source);
                 MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
                ClientPreparedTravel travel = ClientTravelTestFixtures.travel(scope.sent::add);
                set(travel, "prediction", SeamlessTravelFixtures.prediction(source, scope.connection, seamless));
                WormholesClient client = mock(WormholesClient.class);
                when(client.preparedTravel()).thenReturn(travel);
                clients.when(WormholesClient::instance).thenReturn(client);
                assertTrue(travel.pendingCrossing());
                assertTrue(travel.suppressesMovement() != seamless);
                CallbackInfo callback = mock(CallbackInfo.class);
                PreparedTravelPlayerMixin mixin = mock(PreparedTravelPlayerMixin.class, CALLS_REAL_METHODS);
                Method method = PreparedTravelPlayerMixin.class.getDeclaredMethod("wormholes$sourceMovement", CallbackInfo.class);
                method.setAccessible(true);
                method.invoke(mixin, callback);
                if (seamless) {
                    verify(callback, never()).cancel();
                } else {
                    verify(callback).cancel();
                }
            }
        }
    }

    @Test
    public void seamlessPredictionNeverDefersWorldPackets() throws ReflectiveOperationException {
        ClientLevel source = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(source)) {
            ClientPreparedTravel travel = ClientTravelTestFixtures.travel(scope.sent::add);
            set(travel, "prediction", SeamlessTravelFixtures.prediction(source, scope.connection, true));
            Runnable action = mock(Runnable.class);
            assertFalse(travel.deferWorldPacket(new ClientboundForgetLevelChunkPacket(new ChunkPos(1, 1)), action));
            verify(action, never()).run();
            set(travel, "prediction", SeamlessTravelFixtures.prediction(source, scope.connection, false));
            assertTrue(travel.deferWorldPacket(new ClientboundForgetLevelChunkPacket(new ChunkPos(1, 1)), action));
        }
    }
}
