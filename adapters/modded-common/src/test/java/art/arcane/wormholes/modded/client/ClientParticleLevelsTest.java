package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TrackingEmitter;
import net.minecraft.core.particles.ParticleLimit;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientParticleLevelsTest extends MinecraftTestBase {
    @Test
    public void swappingKeepsEachLevelsParticlesForItsReturn() {
        ClientLevel overworld = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        ClientLevel nether = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        FakeEngine engine = new FakeEngine(overworld);
        ParticleGroup<?> smoke = group(false);
        Particle queued = mock(Particle.class);
        TrackingEmitter emitter = mock(TrackingEmitter.class);
        engine.particles.put(ParticleRenderType.SINGLE_QUADS, smoke);
        engine.pending.add(queued);
        engine.emitters.add(emitter);
        ClientParticleLevels levels = new ClientParticleLevels();

        levels.swap(engine, nether);

        assertSame(nether, engine.level);
        assertTrue(engine.particles.isEmpty());
        assertTrue(engine.pending.isEmpty());
        assertTrue(engine.emitters.isEmpty());

        ParticleGroup<?> ash = group(false);
        engine.particles.put(ParticleRenderType.SINGLE_QUADS, ash);
        levels.swap(engine, overworld);

        assertSame(overworld, engine.level);
        assertSame(smoke, engine.particles.get(ParticleRenderType.SINGLE_QUADS));
        assertSame(queued, engine.pending.peek());
        assertSame(emitter, engine.emitters.peek());

        levels.swap(engine, nether);

        assertSame(ash, engine.particles.get(ParticleRenderType.SINGLE_QUADS));
        assertEquals(1, levels.stashed());
    }

    @Test
    public void swappingIntoTheSameLevelChangesNothing() {
        ClientLevel overworld = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        FakeEngine engine = new FakeEngine(overworld);
        ParticleGroup<?> smoke = group(false);
        engine.particles.put(ParticleRenderType.SINGLE_QUADS, smoke);
        ClientParticleLevels levels = new ClientParticleLevels();

        levels.swap(engine, overworld);

        assertSame(smoke, engine.particles.get(ParticleRenderType.SINGLE_QUADS));
        assertEquals(0, levels.stashed());
    }

    @Test
    public void forgottenLevelsDropTheirParticles() {
        ClientLevel overworld = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        ClientLevel nether = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        FakeEngine engine = new FakeEngine(overworld);
        engine.particles.put(ParticleRenderType.SINGLE_QUADS, group(false));
        ClientParticleLevels levels = new ClientParticleLevels();
        levels.swap(engine, nether);

        levels.forget(overworld);
        levels.swap(engine, overworld);

        assertTrue(engine.particles.isEmpty());
        assertEquals(0, levels.stashed());
    }

    @Test
    public void levelsWithoutParticlesAreNotStashed() {
        ClientLevel overworld = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        ClientLevel nether = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        FakeEngine engine = new FakeEngine(overworld);
        engine.particles.put(ParticleRenderType.SINGLE_QUADS, group(true));
        ClientParticleLevels levels = new ClientParticleLevels();

        levels.swap(engine, nether);

        assertEquals(0, levels.stashed());
    }

    private static ParticleGroup<?> group(boolean empty) {
        ParticleGroup<?> group = mock(ParticleGroup.class);
        when(group.isEmpty()).thenReturn(empty);
        return group;
    }

    private static final class FakeEngine implements ParticleEngineAccess {
        private final Map<ParticleRenderType, ParticleGroup<?>> particles = new IdentityHashMap<>();
        private final Queue<TrackingEmitter> emitters = new ArrayDeque<>();
        private final Queue<Particle> pending = new ArrayDeque<>();
        private final Object2IntOpenHashMap<ParticleLimit> counts = new Object2IntOpenHashMap<>();
        private ClientLevel level;

        private FakeEngine(ClientLevel level) {
            this.level = level;
        }

        @Override
        public ClientLevel wormholes$level() {
            return level;
        }

        @Override
        public void wormholes$level(ClientLevel level) {
            this.level = level;
        }

        @Override
        public Map<ParticleRenderType, ParticleGroup<?>> wormholes$particles() {
            return particles;
        }

        @Override
        public Queue<TrackingEmitter> wormholes$emitters() {
            return emitters;
        }

        @Override
        public Queue<Particle> wormholes$pending() {
            return pending;
        }

        @Override
        public Object2IntOpenHashMap<ParticleLimit> wormholes$counts() {
            return counts;
        }
    }
}
