package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.mixin.client.PreparedEntityAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

final class SeamlessTravelFixtures {
    static final UUID TOKEN = new UUID(7, 11);
    static final long GENERATION = 5L;
    static final long REVISION = 9L;
    static final Vec3 EXPECTED_ARRIVAL = new Vec3(100.5, 64, 99.2);
    static final Pose DESTINATION = new Pose(new Vec3d(100.5, 64, 99.2), new Vec3d(100.5, 64, 99.5), new Vec3d(100.5, 64, 99.5),
        new Vec3d(0, 0, -0.3), 180, 10, 178, 9, 179, 177, 181, 179);

    private SeamlessTravelFixtures() {
    }

    static TravelMessage.TravelBegin begin(boolean resident, boolean seamless) {
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int z = 4; z <= 8; z++) {
            for (int x = 4; x <= 8; x++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        return new TravelMessage.TravelBegin(TOKEN, GENERATION, new UUID(1, 3), "minecraft:overworld", ClientTravelTestFixtures.geometry(),
            OpticTransform.translation(-100, -64, -100), ResidentTestFixtures.NETHER, new TravelMessage.TravelPose(100.5, 64, 99.2, 180, 10),
            coordinates, ResidentTestFixtures.environment(ResidentTestFixtures.NETHER), 30_000, TravelMessage.ArrivalRules.FRAME,
            resident, resident ? 3 : 0, seamless);
    }

    static TravelMessage.TravelAccept accept(double offset, float yaw) {
        return new TravelMessage.TravelAccept(TOKEN, GENERATION, REVISION, new TravelMessage.TravelPose(EXPECTED_ARRIVAL.x + offset,
            EXPECTED_ARRIVAL.y, EXPECTED_ARRIVAL.z, DESTINATION.yaw() + yaw, DESTINATION.pitch()), new Vec3d(0, 0, -0.25), 3, true, 100L);
    }

    static Object prediction(ClientLevel source, ClientPacketListener connection) throws ReflectiveOperationException {
        Pose motion = new Pose(new Vec3d(0.5, 0, 0.2), new Vec3d(0.5, 0, 0.5), new Vec3d(0.5, 0, 0.5), new Vec3d(0, 0, -0.3),
            180, 10, 178, 9, 179, 177, 181, 179);
        ClientTravelMotion.Carry carry = new ClientTravelMotion.Carry(180, 10, 178, 9, new Vec3d(0.5, 0, 0.2), new Vec3d(0.5, 0, 0.5));
        Class<?> stateType = Class.forName(ClientPreparedTravel.class.getName() + "$PredictionState");
        Constructor<?> state = stateType.getDeclaredConstructors()[0];
        state.setAccessible(true);
        Object value = state.newInstance(source, motion, carry, DESTINATION, EXPECTED_ARRIVAL, REVISION, mock(LevelExtractor.class),
            connection);
        Class<?> predictionType = Class.forName(ClientPreparedTravel.class.getName() + "$Prediction");
        Constructor<?> prediction = predictionType.getDeclaredConstructor(stateType);
        prediction.setAccessible(true);
        return prediction.newInstance(value);
    }

    static LocalPlayer player() {
        LocalPlayer player = mock(LocalPlayer.class, withSettings().extraInterfaces(PreparedEntityAccess.class));
        when(player.position()).thenReturn(new Vec3(100.5, 64, 99.0));
        when(player.oldPosition()).thenReturn(new Vec3(100.5, 64, 99.2));
        when(player.getDeltaMovement()).thenReturn(new Vec3(0, 0, -0.3));
        when(player.getYRot()).thenReturn(185.0F);
        when(player.getXRot()).thenReturn(12.0F);
        player.xo = 100.5;
        player.yo = 64;
        player.zo = 99.2;
        player.yRotO = 183;
        player.xRotO = 11;
        player.yBodyRot = 184;
        player.yBodyRotO = 182;
        player.yHeadRot = 186;
        player.yHeadRotO = 184;
        return player;
    }

    static ClientTravelChunks chunks(TravelMessage.TravelBegin begin) {
        ClientTravelChunks chunks = new ClientTravelChunks(begin);
        List<TravelMessage.TravelChunkRevision> revisions = new ArrayList<>();
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            revisions.add(new TravelMessage.TravelChunkRevision(coordinate.x(), coordinate.z(), 1));
        }
        chunks.end(new TravelMessage.TravelEnd(TOKEN, GENERATION, REVISION, revisions));
        return chunks;
    }
}
