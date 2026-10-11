package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Box;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.phys.Vec3;

public final class ClientViewRimParticles {
    private static Scope scope;

    private ClientViewRimParticles() {
    }

    static void begin(Box rim) {
        if (scope != null) {
            throw new IllegalStateException("Rim particle observation is already active");
        }
        assertNativeState(Minecraft.getInstance());
        scope = new Scope(new Box(rim));
    }

    static void focused(boolean focused) {
        scope.focused = focused;
    }

    static Snapshot snapshot() {
        Minecraft client = Minecraft.getInstance();
        assertNativeState(client);
        ParticleEngineAccess engine = (ParticleEngineAccess) client.particleEngine;
        return new Snapshot(scope.focusChecks, scope.focusedChecks, scope.dustCalls, scope.createdParticles,
            scope.focused, scope.totalDustCalls, scope.creationCallbacks, scope.nullParticles, scope.unqueuedParticles,
            scope.lastDust, scope.lastUnmatchedDust,
            new Vec3(scope.rim.getXa(), scope.rim.getYa(), scope.rim.getZa()),
            new Vec3(scope.rim.getXb(), scope.rim.getYb(), scope.rim.getZb()), client.gameRenderer.mainCamera().position(),
            engine.wormholes$level() == client.level, engine.wormholes$pending().size(), client.options.particles().get());
    }

    static void end() {
        scope = null;
    }

    public static boolean logicalWindowActive(Minecraft client, boolean actual) {
        if (scope == null) {
            return actual;
        }
        assertNativeState(client);
        scope.focusChecks++;
        if (scope.focused) {
            scope.focusedChecks++;
        }
        return scope.focused;
    }

    public static boolean beginDust(ParticleOptions options, double x, double y, double z) {
        if (scope == null) {
            return false;
        }
        assertNativeState(Minecraft.getInstance());
        boolean previous = scope.emitting;
        Box rim = scope.rim;
        scope.emitting = options instanceof DustParticleOptions
            && boundary(x, rim.getXa(), rim.getXb()) && boundary(y, rim.getYa(), rim.getYb())
            && boundary(z, rim.getZa(), rim.getZb());
        if (scope.emitting) {
            scope.dustCalls++;
            scope.lastDust = new Vec3(x, y, z);
        }
        if (options instanceof DustParticleOptions) {
            scope.totalDustCalls++;
            if (!scope.emitting) {
                scope.lastUnmatchedDust = new Vec3(x, y, z);
            }
        }
        return previous;
    }

    public static void endDust(boolean previous) {
        if (scope != null) {
            scope.emitting = previous;
        }
    }

    public static void created(ParticleEngine engine, ParticleOptions options, Particle particle) {
        if (scope == null || !scope.emitting || !(options instanceof DustParticleOptions)) {
            return;
        }
        assertNativeState(Minecraft.getInstance());
        scope.creationCallbacks++;
        if (particle == null) {
            scope.nullParticles++;
            return;
        }
        if (!((ParticleEngineAccess) engine).wormholes$pending().contains(particle)) {
            scope.unqueuedParticles++;
            return;
        }
        scope.createdParticles++;
    }

    private static boolean boundary(double value, double min, double max) {
        return Math.abs(value - min) < 0.000001D || Math.abs(value - max) < 0.000001D;
    }

    private static void assertNativeState(Minecraft client) {
        if (!client.isSameThread() || client.isPaused() || !NativeTestWindow.hiddenAndUnfocused(client)
            || !NativeTestWindow.mouseReleased(client)) {
            throw new AssertionError("Rim acceptance requires an unpaused hidden, unfocused native client with its mouse released");
        }
    }

    public record Snapshot(long focusChecks, long focusedChecks, long dustCalls, long createdParticles,
                           boolean logicalActive, long totalDustCalls, long creationCallbacks, long nullParticles,
                           long unqueuedParticles, Vec3 lastDust, Vec3 lastUnmatchedDust, Vec3 rimMin, Vec3 rimMax,
                           Vec3 camera, boolean engineLevelMatches, int pendingParticles, ParticleStatus particleStatus) {
    }

    private static final class Scope {
        private final Box rim;
        private boolean focused;
        private boolean emitting;
        private long focusChecks;
        private long focusedChecks;
        private long dustCalls;
        private long createdParticles;
        private long totalDustCalls;
        private long creationCallbacks;
        private long nullParticles;
        private long unqueuedParticles;
        private Vec3 lastDust;
        private Vec3 lastUnmatchedDust;

        private Scope(Box rim) {
            this.rim = rim;
        }
    }
}
