package art.arcane.optics.fidelity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.claim.RecordingProjectionOutput;

final class AcousticsBridgeTest {
    private static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_PORTAL = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID DEST_WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID OBSERVER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final FidelityOptions FIDELITY = new FidelityOptions(0.005D, 0.6D, true, false, false, 8, 24.0D, 8);

    private final RecordingProjectionOutput<UUID> output = new RecordingProjectionOutput<UUID>();
    private final UUID observer = OBSERVER;

    @Test
    void profilesGateEventClassesAndTheAmbientBed() {
        AcousticsBridge<UUID> bridge = bridge();
        AcousticsBridge.SoundEvent block = event(DEST_WORLD, 102.0D, 64.0D, 100.0D, "minecraft:block.stone.break", AcousticsProfile.SoundClass.WORLD);
        AcousticsBridge.SoundEvent hurt = event(DEST_WORLD, 102.0D, 64.0D, 100.0D, "minecraft:entity.generic.hurt", AcousticsProfile.SoundClass.ENTITY);

        note(bridge, AcousticsProfile.OFF, AcousticsBridge.Environment.NETHER, 0L);
        assertEquals(0, bridge.onEvent(block, 0L));
        assertEquals(0, bridge.tickAmbient(10_000L));

        note(bridge, AcousticsProfile.AMBIENT, AcousticsBridge.Environment.NETHER, 20_000L);
        assertEquals(0, bridge.onEvent(block, 20_000L));
        assertEquals(1, bridge.tickAmbient(20_000L));
        assertEquals("minecraft:ambient.nether_wastes.loop", played().get(0).soundKey());
        assertEquals(AcousticsProfile.SoundClass.AMBIENT, played().get(0).soundClass());
        assertEquals(0, bridge.tickAmbient(21_000L), "the ambient bed repeats every four seconds, not every tick");
        assertEquals(1, bridge.tickAmbient(24_100L));

        note(bridge, AcousticsProfile.AMBIENT_EVENTS, AcousticsBridge.Environment.NETHER, 30_000L);
        assertEquals(1, bridge.onEvent(block, 30_000L));
        assertEquals(0, bridge.onEvent(hurt, 30_000L), "entity sounds need the full profile");

        note(bridge, AcousticsProfile.FULL, AcousticsBridge.Environment.NETHER, 40_000L);
        assertEquals(1, bridge.onEvent(hurt, 40_000L));
    }

    @Test
    void soundsPlayAtTheApertureWithDistanceAttenuationAndRespectTheRadius() {
        AcousticsBridge<UUID> bridge = bridge();
        note(bridge, AcousticsProfile.FULL, AcousticsBridge.Environment.NORMAL, 0L);

        assertEquals(1, bridge.onEvent(event(DEST_WORLD, 106.0D, 64.0D, 100.0D, "minecraft:block.stone.break", AcousticsProfile.SoundClass.WORLD), 0L));
        AcousticsBridge.Playback near = played().get(0);
        assertEquals(10.5D, near.x(), 1.0E-9D, "sounds are positioned at the local aperture centre");
        assertEquals(65.0D, near.y(), 1.0E-9D);
        assertEquals(10.5D, near.z(), 1.0E-9D);
        assertEquals(SoundAttenuation.volume(1.0F, Math.sqrt(5.5D * 5.5D + 1.0D + 0.25D), 24.0D), near.volume(), 1.0E-6F,
            "volume follows the event's distance from the destination anchor");

        assertEquals(1, bridge.onEvent(event(DEST_WORLD, 120.0D, 64.0D, 100.0D, "minecraft:block.stone.break", AcousticsProfile.SoundClass.WORLD), 0L));
        assertTrue(played().get(1).volume() < near.volume(), "farther destination events arrive quieter");

        assertEquals(0, bridge.onEvent(event(DEST_WORLD, 140.0D, 64.0D, 100.0D, "minecraft:block.stone.break", AcousticsProfile.SoundClass.WORLD), 0L),
            "events past the destination radius are ignored");
        assertEquals(0, bridge.onEvent(event(OTHER_WORLD, 102.0D, 64.0D, 100.0D, "minecraft:block.stone.break", AcousticsProfile.SoundClass.WORLD), 0L),
            "events in worlds no portal looks into are ignored");
        assertTrue(bridge.hasDestinationWorld(DEST_WORLD));
        assertFalse(bridge.hasDestinationWorld(OTHER_WORLD));
    }

    @Test
    void theRateCapHoldsPerObserverPerSecondAcrossPortals() {
        AcousticsBridge<UUID> bridge = bridge();
        note(bridge, AcousticsProfile.FULL, AcousticsBridge.Environment.NORMAL, 0L);
        bridge.noteDestination(OTHER_PORTAL, DEST_WORLD, 100.5D, 65.0D, 100.5D, 40.5D, 65.0D, 40.5D,
            AcousticsProfile.FULL, AcousticsBridge.Environment.NORMAL, false, 0L);
        AcousticsBridge.SoundEvent event = event(DEST_WORLD, 101.0D, 64.0D, 100.0D, "minecraft:block.stone.break", AcousticsProfile.SoundClass.WORLD);

        int plays = 0;
        for (int index = 0; index < 20; index++) {
            plays += bridge.onEvent(event, 100L + index);
        }
        assertEquals(8, plays, "eight sounds per second per observer regardless of how many portals qualify");
        assertEquals(2, bridge.onEvent(event, 1_200L), "a new second admits the two portals again");
    }

    @Test
    void remoteSoundsReachThePortalsLinkedToThatRemotePortal() {
        AcousticsBridge<UUID> bridge = bridge();
        UUID remotePortal = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        bridge.noteRemoteDestination(PORTAL, "beta", remotePortal, 100.5D, 65.0D, 100.5D, 10.5D, 65.0D, 10.5D,
            AcousticsProfile.AMBIENT_EVENTS, 0L);

        assertEquals(1, bridge.onRemoteSound("beta", remotePortal, 103.0D, 64.0D, 100.0D, "minecraft:block.stone.break",
            1.0F, 1.0F, AcousticsProfile.SoundClass.WORLD, 0L));
        assertEquals(0, bridge.onRemoteSound("gamma", remotePortal, 103.0D, 64.0D, 100.0D, "minecraft:block.stone.break",
            1.0F, 1.0F, AcousticsProfile.SoundClass.WORLD, 0L), "a different peer's portal id is not ours");
        assertEquals(0, bridge.onRemoteSound("beta", remotePortal, 103.0D, 64.0D, 100.0D, "minecraft:entity.generic.hurt",
            1.0F, 1.0F, AcousticsProfile.SoundClass.ENTITY, 0L), "the portal profile gates remote sounds too");
    }

    @Test
    void staleAperturesExpireSoUnprojectedPortalsStopListening() {
        AcousticsBridge<UUID> bridge = bridge();
        note(bridge, AcousticsProfile.FULL, AcousticsBridge.Environment.NORMAL, 0L);
        assertTrue(bridge.hasDestinationWorld(DEST_WORLD));
        bridge.tickAmbient(AcousticsBridge.APERTURE_TTL_MILLIS + 1L);
        assertFalse(bridge.hasDestinationWorld(DEST_WORLD));
    }

    @Test
    void clientRunBedsSkipThePacketBedAndExposeTheBedToTheSession() {
        output.ambientClients.add(observer);
        AcousticsBridge<UUID> bridge = bridge();
        assertEquals(null, bridge.ambientBed(PORTAL));
        note(bridge, AcousticsProfile.AMBIENT, AcousticsBridge.Environment.THE_END, 0L);

        assertEquals(0, bridge.tickAmbient(5_000L), "clients that run the bed get no packet bed");
        assertTrue(played().isEmpty());
        AcousticsBridge.Playback bed = bridge.ambientBed(PORTAL);
        assertEquals("minecraft:ambient.cave", bed.soundKey());
        assertEquals(AcousticsProfile.SoundClass.AMBIENT, bed.soundClass());
        assertEquals(10.5D, bed.x());
        assertEquals(AcousticsBridge.AMBIENT_VOLUME, bed.volume());

        note(bridge, AcousticsProfile.OFF, AcousticsBridge.Environment.THE_END, 6_000L);
        assertEquals(null, bridge.ambientBed(PORTAL), "a profile without the ambient bed exposes none");
        note(bridge, AcousticsProfile.AMBIENT, AcousticsBridge.Environment.NORMAL, 7_000L);
        assertEquals(null, bridge.ambientBed(PORTAL), "a calm overworld has no bed");
    }

    private AcousticsBridge<UUID> bridge() {
        output.everyone = List.of(observer);
        return new AcousticsBridge<>(new AcousticsBridge.Options<>(output, viewer -> viewer, () -> FIDELITY));
    }

    private List<AcousticsBridge.Playback> played() {
        List<AcousticsBridge.Playback> played = new ArrayList<AcousticsBridge.Playback>(output.sounds.size());
        for (RecordingProjectionOutput.Emitted<UUID, AcousticsBridge.Playback> sent : output.sounds) {
            assertEquals(observer, sent.observer());
            played.add(sent.value());
        }
        return played;
    }

    private static void note(AcousticsBridge<UUID> bridge, AcousticsProfile profile, AcousticsBridge.Environment environment, long now) {
        bridge.noteDestination(PORTAL, DEST_WORLD, 100.5D, 65.0D, 100.5D, 10.5D, 65.0D, 10.5D, profile, environment, false, now);
    }

    private static AcousticsBridge.SoundEvent event(UUID world, double x, double y, double z, String key, AcousticsProfile.SoundClass soundClass) {
        return new AcousticsBridge.SoundEvent(world, x, y, z, key, 1.0F, 1.0F, soundClass);
    }
}
