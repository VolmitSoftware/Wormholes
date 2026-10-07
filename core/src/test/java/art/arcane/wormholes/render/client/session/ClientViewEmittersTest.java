package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.AmbientSparkCadence;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.math.Box;
import art.arcane.wormholes.network.client.FxMessage;

class ClientViewEmittersTest {
    private static final Box AREA = new Box(10.0D, 13.0D, 64.0D, 67.0D, 20.0D, 21.0D);

    @Test
    void rimCoversEveryCornerWithAQuantizedColor() {
        List<FxMessage.FxEmitter> out = new ArrayList<FxMessage.FxEmitter>();
        ClientViewEmitters.rim(AREA, 250, 7, 0, 10, out);
        assertEquals(8, out.size());
        for (FxMessage.FxEmitter emitter : out) {
            assertEquals(FxMessage.FxKind.RIM_DUST, emitter.kind());
            assertEquals(0xFF0000, (int) emitter.paramA());
            assertEquals(10, emitter.ticks());
            assertTrue(emitter.x() == 10.0D || emitter.x() == 13.0D);
            assertTrue(emitter.y() == 64.0D || emitter.y() == 67.0D);
        }
        List<FxMessage.FxEmitter> timed = new ArrayList<FxMessage.FxEmitter>();
        ClientViewEmitters.rim(AREA, 129, 255, 0, 10, timed);
        List<FxMessage.FxEmitter> step = new ArrayList<FxMessage.FxEmitter>();
        ClientViewEmitters.rim(AREA, 131, 255, 0, 10, step);
        assertEquals(timed, step, "colors inside one progress step produce the same emitter set");
    }

    @Test
    void sparksCarryTheirCadenceInFlags() {
        List<FxMessage.FxEmitter> out = new ArrayList<FxMessage.FxEmitter>();
        ClientViewEmitters.ambient(new ClientViewEmitters.Ambient(AmbientParticleStyle.SPARKS, 0xFFFFFF, true, 3, 5, AREA, List.of()), out);
        assertEquals(1, out.size());
        FxMessage.FxEmitter sparks = out.get(0);
        assertEquals(FxMessage.FxKind.SURFACE, sparks.kind());
        assertEquals(ClientViewEmitters.SPARK_PARTICLE, sparks.key());
        assertEquals(5, sparks.ticks());
        assertEquals(ClientViewEmitters.SURFACE_OPEN_FLAG, sparks.flags() & ClientViewEmitters.SURFACE_OPEN_FLAG);
        assertEquals(3, sparks.flags() >>> ClientViewEmitters.SURFACE_INTERVAL_SHIFT);
        assertEquals((float) AmbientSparkCadence.CELL_SPREAD, sparks.paramA());
    }

    @Test
    void rotatingStylesSpreadEachPointOverTheirWindow() {
        List<FxMessage.FxEmitter> closedCorners = new ArrayList<FxMessage.FxEmitter>();
        ClientViewEmitters.ambient(new ClientViewEmitters.Ambient(AmbientParticleStyle.CORNERS, 0x123456, false, 1, 5, AREA, List.of()), closedCorners);
        assertEquals(8, closedCorners.size());
        assertEquals(20, closedCorners.get(0).ticks(), "two corners per step visit all eight corners every four steps");
        List<double[]> outline = new ArrayList<double[]>();
        for (int i = 0; i < 400; i++) {
            outline.add(new double[] {i, 64.0D, 20.0D});
        }
        List<FxMessage.FxEmitter> open = new ArrayList<FxMessage.FxEmitter>();
        ClientViewEmitters.ambient(new ClientViewEmitters.Ambient(AmbientParticleStyle.OUTLINE, 0x123456, true, 1, 1, AREA, outline), open);
        assertEquals(FxMessage.MAX_FX_EMITTERS, open.size());
        assertEquals(13, open.get(0).ticks());
        List<FxMessage.FxEmitter> off = new ArrayList<FxMessage.FxEmitter>();
        ClientViewEmitters.ambient(new ClientViewEmitters.Ambient(AmbientParticleStyle.OFF, 0, true, 1, 1, AREA, outline), off);
        assertTrue(off.isEmpty());
    }

    @Test
    void animationsRoundTripTheirModeCenterPlaneAndQuality() {
        Vec3d[] sizes = {new Vec3d(3.0D, 4.0D, 0.0D), new Vec3d(0.0D, 4.0D, 2.0D), new Vec3d(5.0D, 0.0D, 5.0D),
            new Vec3d(0.0D, 1.0D, 0.0D), new Vec3d(1.0D, 1.0D, 1.0D), new Vec3d(2.0D, 0.0D, 0.0D)};
        for (PortalAnimation.Mode mode : PortalAnimation.Mode.values()) {
            for (VisualQualityProfile quality : VisualQualityProfile.values()) {
                for (Vec3d size : sizes) {
                    Vec3d center = new Vec3d(10.5D, 66.25D, -20.5D);
                    FxMessage.FxEmitter emitter = ClientViewEmitters.animation(mode, center, size, quality);
                    assertEquals(FxMessage.FxKind.ANIMATION, emitter.kind());
                    assertTrue(ClientViewEmitters.oneShot(emitter));
                    ClientViewEmitters.Animation decoded = ClientViewEmitters.animation(emitter);
                    assertEquals(mode, decoded.mode());
                    assertEquals(quality, decoded.quality());
                    assertEquals(center, decoded.center());
                    int normal = PortalAnimation.normalAxis(size);
                    assertEquals(normal, PortalAnimation.normalAxis(decoded.size()), "normal of " + size);
                    double[] original = {size.x(), size.y(), size.z()};
                    double[] rebuilt = {decoded.size().x(), decoded.size().y(), decoded.size().z()};
                    assertEquals(Math.max(1.0E-3, original[PortalAnimation.planeA(normal)]), rebuilt[PortalAnimation.planeA(normal)], 1.0E-6);
                    assertEquals(Math.max(1.0E-3, original[PortalAnimation.planeB(normal)]), rebuilt[PortalAnimation.planeB(normal)], 1.0E-6);
                }
            }
        }
        assertNull(ClientViewEmitters.animation(new FxMessage.FxEmitter(FxMessage.FxKind.ANIMATION, "", 0.0D, 0.0D, 0.0D, 1.0F, 1.0F,
            0, 0x07)), "an unknown mode is ignored");
        assertNull(ClientViewEmitters.animation(new FxMessage.FxEmitter(FxMessage.FxKind.ANIMATION, "", 0.0D, 0.0D, 0.0D, Float.NaN,
            1.0F, 0, 0)), "a non-finite plane is ignored");
    }

    @Test
    void burstsCarryCountSpreadsAndSpeed() {
        FxMessage.FxEmitter burst = ClientViewEmitters.burst("minecraft:reverse_portal", 1.0D, 2.0D, 3.0D, 12, 0.4D, 0.6D, 0.4D);
        assertEquals(FxMessage.FxKind.BURST, burst.kind());
        assertEquals(12, burst.ticks());
        assertEquals(0.4F, burst.paramA());
        assertEquals(0.6F, burst.paramB());
        assertEquals(0.4D, ClientViewEmitters.burstSpeed(burst), 1.0E-9);
        assertTrue(ClientViewEmitters.oneShot(burst), "a burst fires once even though its tick field carries the count");
        assertEquals(1, ClientViewEmitters.burst("minecraft:smoke", 0.0D, 0.0D, 0.0D, 0, 0.0D, 0.0D, 9.0D).ticks());
        assertEquals(255, ClientViewEmitters.burst("minecraft:smoke", 0.0D, 0.0D, 0.0D, 1, 0.0D, 0.0D, 9.0D).flags());
    }

    @Test
    void soundsAndDustFireOnceOnlyWithoutAnInterval() {
        AcousticsBridge.Playback playback = new AcousticsBridge.Playback("minecraft:block.stone.break", AcousticsProfile.SoundClass.WORLD, 1.0D,
            2.0D, 3.0D, 0.5F, 0.8F);
        FxMessage.FxEmitter event = ClientViewEmitters.sound(playback, 0);
        assertTrue(ClientViewEmitters.oneShot(event));
        assertEquals(AcousticsProfile.SoundClass.WORLD, ClientViewEmitters.soundClass(event));
        FxMessage.FxEmitter bed = ClientViewEmitters.sound(playback, AcousticsBridge.AMBIENT_INTERVAL_TICKS);
        assertFalse(ClientViewEmitters.oneShot(bed));
        assertEquals(AcousticsBridge.AMBIENT_INTERVAL_TICKS, bed.ticks());
        FxMessage.FxEmitter dust = ClientViewEmitters.dust(1.0D, 2.0D, 3.0D, 255, 70, 70);
        assertEquals(FxMessage.FxKind.RIM_DUST, dust.kind());
        assertEquals(0xFF4646, (int) dust.paramA());
        assertTrue(ClientViewEmitters.oneShot(dust));
        List<FxMessage.FxEmitter> rim = new ArrayList<FxMessage.FxEmitter>();
        ClientViewEmitters.rim(AREA, 255, 0, 0, 5, rim);
        assertFalse(ClientViewEmitters.oneShot(rim.get(0)));
    }
}
