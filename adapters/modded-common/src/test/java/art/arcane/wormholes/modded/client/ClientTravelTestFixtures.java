package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.network.client.TravelMessage;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

final class ClientTravelTestFixtures {
    private ClientTravelTestFixtures() {
    }

    static ClientPreparedTravel travel(Consumer<TravelMessage> sender) {
        return new ClientPreparedTravel(sender, new ResidentLevels(sender, 512L << 20));
    }

    static ApertureDescriptor geometry() {
        return geometry(true, 63);
    }

    static ApertureDescriptor geometry(boolean front, long mask) {
        return new ApertureDescriptor(0, 0, 0, Face.N.ordinal(), front, 0, false, 2, 3, new long[]{mask},
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 11, List.of());
    }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
