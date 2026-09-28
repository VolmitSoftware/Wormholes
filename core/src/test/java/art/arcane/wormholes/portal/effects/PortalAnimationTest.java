package art.arcane.wormholes.portal.effects;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.geometry.GeometryVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalAnimationTest {
    @Test
    void openingHasPreludeImpactAndDelayedBoom() {
        Recorder host = new Recorder();
        PortalAnimation<Integer> animation = new PortalAnimation<>(options(PortalAnimation.Mode.OPEN, true, () -> true), host);
        for (int tick = 0; tick < 18; tick++) {
            assertTrue(animation.tick());
        }
        assertEquals(List.of("block.end_portal.spawn"), host.sounds);
        assertTrue(host.particles.contains(PortalAnimation.Particle.STREAM_DUST));
        assertTrue(animation.tick());
        assertEquals(3, host.sounds.size());
        for (int tick = 0; tick < 5; tick++) {
            animation.tick();
        }
        assertEquals("entity.warden.sonic_boom", host.sounds.getLast());
        assertTrue(host.particles.contains(PortalAnimation.Particle.PURPLE_FLASH));
        assertTrue(host.particles.contains(PortalAnimation.Particle.ARM_DUST));
        for (int tick = 0; tick < 20; tick++) {
            animation.tick();
        }
        assertFalse(animation.tick());
        assertEquals(4, host.sounds.size());
    }

    @Test
    void reversalCancelsDisplayAndPendingSounds() {
        AtomicBoolean active = new AtomicBoolean(true);
        Recorder host = new Recorder();
        PortalAnimation<Integer> animation = new PortalAnimation<>(options(PortalAnimation.Mode.CLOSE, true, active::get), host);
        assertTrue(animation.tick());
        assertEquals(1, host.created);
        active.set(false);
        assertFalse(animation.tick());
        assertEquals(1, host.removed);
        assertEquals(List.of("block.ender_chest.close"), host.sounds);
        animation.close();
        assertEquals(1, host.removed);
    }

    @Test
    void disabledParticlesPreserveOpeningSoundsWithoutVisuals() {
        Recorder host = new Recorder();
        PortalAnimation<Integer> animation = new PortalAnimation<>(options(PortalAnimation.Mode.OPEN, false, () -> true), host);
        for (int tick = 0; tick < 10; tick++) {
            animation.tick();
        }
        assertEquals(4, host.sounds.size());
        assertTrue(host.particles.isEmpty());
        assertEquals(0, host.created);
    }

    @Test
    void closeCracksCoverTheEntirePaneAndKeepSecondaryBranches() {
        Recorder host = new Recorder();
        PortalAnimation<Integer> animation = new PortalAnimation<>(options(PortalAnimation.Mode.CLOSE, true, () -> true), host);
        for (int tick = 0; tick < 10; tick++) {
            animation.tick();
        }
        host.emissions.clear();
        animation.tick();
        List<PortalAnimation.ParticleEmission> cracks = host.emissions.stream().filter(particle -> particle.type() == PortalAnimation.Particle.CRACK_DUST).toList();
        assertEquals(24, cracks.size());
        assertEquals(12, host.emissions.stream().filter(particle -> particle.type() == PortalAnimation.Particle.BRANCHLET_DUST).count());
        assertTrue(cracks.stream().anyMatch(particle -> particle.position().x() < -.5));
        assertTrue(cracks.stream().anyMatch(particle -> particle.position().x() > .5));
        assertTrue(cracks.stream().anyMatch(particle -> particle.position().y() < -.5));
        assertTrue(cracks.stream().anyMatch(particle -> particle.position().y() > .5));
    }

    @Test
    void rejectedPreludeSchedulerStillDeliversImpactAndBoomOnce() {
        Recorder host = new Recorder();
        PortalAnimation<Integer> animation = new PortalAnimation<>(options(PortalAnimation.Mode.PRELUDE, true, () -> true), host);
        animation.tick();
        animation.schedulingRejected();
        assertTrue(host.particles.contains(PortalAnimation.Particle.PURPLE_FLASH));
        assertEquals(List.of("block.end_portal.spawn", "block.beacon.activate", "entity.warden.sonic_boom"), host.sounds);
        animation.schedulingRejected();
        assertEquals(3, host.sounds.size());
    }

    @Test
    void rejectedSoundOnlyOpeningDoesNotRepeatItsImpactSounds() {
        Recorder host = new Recorder();
        PortalAnimation<Integer> animation = new PortalAnimation<>(options(PortalAnimation.Mode.OPEN, false, () -> true), host);
        animation.tick();
        animation.schedulingRejected();
        assertEquals(List.of("block.end_portal.spawn", "block.end_portal.spawn", "block.beacon.activate", "entity.warden.sonic_boom"), host.sounds);
        assertTrue(host.particles.isEmpty());
    }

    @Test
    void formationMovesConsumedBlocksThenRemovesThemBeforeImpact() {
        Recorder host = new Recorder();
        PortalAnimation.Options configuration = options(PortalAnimation.Mode.FORMATION, true, () -> true);
        configuration = new PortalAnimation.Options(configuration.mode(), configuration.center(), configuration.size(), configuration.quality(),
            configuration.particles(), configuration.volume(), configuration.active(), configuration.audible(),
            List.of(new PortalAnimation.Block(new GeometryVector(2, 3, 0), "minecraft:glass")));
        PortalAnimation<Integer> animation = new PortalAnimation<>(configuration, host);
        for (int tick = 0; tick <= 20; tick++) {
            animation.tick();
        }
        assertEquals(1, host.created);
        assertEquals(20, host.transformed);
        assertEquals(0, host.removed);
        animation.tick();
        assertEquals(1, host.removed);
        assertTrue(host.particles.contains(PortalAnimation.Particle.PURPLE_FLASH));
    }

    private PortalAnimation.Options options(PortalAnimation.Mode mode, boolean particles, BooleanSupplier active) {
        return new PortalAnimation.Options(mode, new GeometryVector(0, 0, 0), new GeometryVector(3, 4, 1), VisualQualityProfile.BALANCED,
            particles, 1, active, () -> true, List.of());
    }

    private static final class Recorder implements PortalAnimation.Host<Integer> {
        private final List<String> sounds = new ArrayList<>();
        private final List<PortalAnimation.Particle> particles = new ArrayList<>();
        private final List<PortalAnimation.ParticleEmission> emissions = new ArrayList<>();
        private int created;
        private int removed;
        private int transformed;

        public void particle(PortalAnimation.ParticleEmission emission) { particles.add(emission.type()); emissions.add(emission); }
        public void sound(PortalAnimation.SoundEmission emission) { sounds.add(emission.id()); }
        public Integer spawn(PortalAnimation.DisplaySpec display) { return ++created; }
        public void transform(Integer display, PortalAnimation.DisplaySpec transform) { transformed++; }
        public void remove(Integer display) { removed++; }
    }
}
