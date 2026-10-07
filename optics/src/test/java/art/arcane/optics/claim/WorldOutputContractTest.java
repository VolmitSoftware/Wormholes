package art.arcane.optics.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.junit.jupiter.api.Test;

import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.FidelityOptions;
import art.arcane.optics.fidelity.BlockEntityLayer;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.spi.OpticsMetrics;

final class WorldOutputContractTest {
    private static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID DESTINATION_WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final FidelityOptions FIDELITY = new FidelityOptions(0.005D, 0.6D, true, false, false, 8, 24.0D, 8);

    @Test
    void blockEntitiesReachTheOutputAndAreCountedThroughTheMetrics() {
        RecordingProjectionOutput<String> output = new RecordingProjectionOutput<String>();
        CountingMetrics metrics = new CountingMetrics();
        BlockEntityLayer<String> layer = new BlockEntityLayer<String>(metrics);
        Long2ObjectOpenHashMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<BlockEntitySample>();
        desired.put(CellKeys.pack(1, 64, 0), sample("sign"));
        desired.put(CellKeys.pack(2, 64, 0), sample("banner"));

        layer.update(desired, (x, y, z) -> null);
        assertEquals(2, layer.flush("observer", 64, output));

        assertEquals(2, output.blockEntities.size());
        for (RecordingProjectionOutput.Emitted<String, RecordingProjectionOutput.BlockEntitySend> sent : output.blockEntities) {
            assertEquals("observer", sent.observer());
            assertEquals(desired.get(sent.value().cellKey()), sent.value().sample());
        }
        assertEquals(2L, metrics.counts.get(BlockEntityLayer.SENT_METRIC));
    }

    @Test
    void weatherBurstsLandOnProjectedAirCellsOfTheObserver() {
        RecordingProjectionOutput<String> output = new RecordingProjectionOutput<String>();
        WeatherRelay relay = new WeatherRelay();
        Long2ObjectOpenHashMap<BlockClaim<String, Object>> claims = new Long2ObjectOpenHashMap<BlockClaim<String, Object>>();
        for (int x = 0; x < 30; x++) {
            claims.put(CellKeys.pack(x, 70, 0), new BlockClaim<String, Object>("air", null, BlockClaim.NO_REMOTE_KEY, false));
            claims.put(CellKeys.pack(x, 69, 0), new BlockClaim<String, Object>("stone", null, BlockClaim.NO_REMOTE_KEY, false));
        }

        relay.spawn(new WeatherRelay.Emission<String, Object>(claims, relay.plan(true, false, "minecraft:snowy_plains", 0L), new Random(7L),
            "air"::equals), output, "observer");

        assertEquals(12, output.weather.size());
        for (RecordingProjectionOutput.Emitted<String, RecordingProjectionOutput.WeatherSend> sent : output.weather) {
            assertEquals("observer", sent.observer());
            assertEquals(WeatherRelay.Precipitation.SNOWFLAKE, sent.value().particle());
            assertEquals(70, CellKeys.unpackY(sent.value().cellKey()), "only projected air cells receive particles");
        }
    }

    @Test
    void soundsFanOutToTheObserversTheOutputNamesAndAmbientClientsSkipThePacketBed() {
        RecordingProjectionOutput<String> output = new RecordingProjectionOutput<String>();
        output.observers.put(PORTAL, List.of("packet", "client"));
        output.ambientClients.add("client");
        AcousticsBridge<String> bridge = new AcousticsBridge<String>(new AcousticsBridge.Options<String>(output,
            name -> UUID.nameUUIDFromBytes(name.getBytes()), () -> FIDELITY));
        bridge.noteDestination(PORTAL, DESTINATION_WORLD, 100.5D, 65.0D, 100.5D, 10.5D, 65.0D, 10.5D, AcousticsProfile.FULL,
            AcousticsBridge.Environment.NETHER, false, 0L);

        assertEquals(2, bridge.onEvent(new AcousticsBridge.SoundEvent(DESTINATION_WORLD, 102.0D, 64.0D, 100.0D, "minecraft:block.stone.break", 1.0F,
            1.0F, AcousticsProfile.SoundClass.WORLD), 0L));
        List<String> heard = new ArrayList<String>();
        for (RecordingProjectionOutput.Emitted<String, AcousticsBridge.Playback> sent : output.sounds) {
            heard.add(sent.observer());
            assertEquals(10.5D, sent.value().x(), 1.0E-9D);
        }
        assertEquals(List.of("packet", "client"), heard);

        output.sounds.clear();
        assertEquals(1, bridge.tickAmbient(5_000L));
        assertEquals(1, output.sounds.size());
        assertEquals("packet", output.sounds.get(0).observer());
        assertEquals(AcousticsProfile.SoundClass.AMBIENT, output.sounds.get(0).value().soundClass());
        assertTrue(output.blockEntities.isEmpty() && output.weather.isEmpty() && output.lights.isEmpty());
    }

    private static BlockEntitySample sample(String type) {
        return new BlockEntitySample("minecraft:" + type, new byte[] {1, 0, 0});
    }

    private static final class CountingMetrics implements OpticsMetrics {
        private final Map<String, Long> counts = new HashMap<String, Long>();

        @Override
        public void failure(String reason) {
        }

        @Override
        public void packet() {
        }

        @Override
        public void count(String key, long delta) {
            counts.merge(key, Long.valueOf(delta), Long::sum);
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }
}
