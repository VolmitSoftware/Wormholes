package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.wormholes.modded.mixin.client.DhPortalBlazeTemporalAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.world.IDhClientWorld;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class DhBlazeTemporalViews {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long EXPIRE_NANOS = 60_000_000_000L;
    private static final int MAX_VIEWS = 12;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final LinkedHashMap<DhPortalLodViews.Path, History> HISTORIES = new LinkedHashMap<>(16, 0.75F, true);
    private static long cacheGeneration;
    private static Scope active;
    private static DhPortalLodViews.MainPath mainPath;

    private DhBlazeTemporalViews() {
    }

    public static Scope open(RenderParams parameters, DhPortalBlazeTemporalAccess access) {
        RenderSystem.assertOnRenderThread();
        reconcile();
        DhPortalLodViews.Path path = DhPortalRenderContext.temporalPath(parameters);
        if (path instanceof DhPortalLodViews.MainPath main) {
            mainPath = main;
        }
        History history = HISTORIES.get(path);
        if (history != null && (history.world != parameters.dhClientWorld || history.level != parameters.dhClientLevel)) {
            HISTORIES.remove(path);
            retire(history);
            history = null;
        }
        if (history == null) {
            history = new History(parameters);
            HISTORIES.put(path, history);
        }
        history.lastUsed = System.nanoTime();
        history.users++;
        Scope scope = new Scope(access, history);
        active = scope;
        try {
            history.state.apply(access);
            trim();
            return scope;
        } catch (RuntimeException | Error failure) {
            try {
                scope.close();
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    public static void tick() {
        RenderSystem.assertOnRenderThread();
        reconcile();
        long cutoff = System.nanoTime() - EXPIRE_NANOS;
        Iterator<Map.Entry<DhPortalLodViews.Path, History>> iterator = HISTORIES.entrySet().iterator();
        while (iterator.hasNext()) {
            History history = iterator.next().getValue();
            if (history.users == 0 && history.lastUsed - cutoff < 0L) {
                iterator.remove();
                retire(history);
            }
        }
        trim();
    }

    public static void invalidate() {
        GENERATION.incrementAndGet();
        if (RenderSystem.isOnRenderThread()) {
            reconcile();
        } else {
            Minecraft.getInstance().execute(DhBlazeTemporalViews::reconcile);
        }
    }

    public static void closeLevel(IDhClientLevel level) {
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(() -> closeLevel(level));
            return;
        }
        Iterator<Map.Entry<DhPortalLodViews.Path, History>> iterator = HISTORIES.entrySet().iterator();
        while (iterator.hasNext()) {
            History history = iterator.next().getValue();
            if (history.level == level) {
                iterator.remove();
                retire(history);
            }
        }
    }

    private static void reconcile() {
        RenderSystem.assertOnRenderThread();
        long generation = GENERATION.get();
        if (cacheGeneration == generation) {
            return;
        }
        cacheGeneration = generation;
        mainPath = null;
        Iterator<Map.Entry<DhPortalLodViews.Path, History>> iterator = HISTORIES.entrySet().iterator();
        while (iterator.hasNext()) {
            History history = iterator.next().getValue();
            iterator.remove();
            retire(history);
        }
    }

    private static void trim() {
        int portals = 0;
        int mains = 0;
        for (DhPortalLodViews.Path path : HISTORIES.keySet()) {
            if (path instanceof DhPortalLodViews.PortalPath) {
                portals++;
            } else {
                mains++;
            }
        }
        Iterator<Map.Entry<DhPortalLodViews.Path, History>> iterator = HISTORIES.entrySet().iterator();
        while ((portals > MAX_VIEWS || mains > MAX_VIEWS) && iterator.hasNext()) {
            Map.Entry<DhPortalLodViews.Path, History> entry = iterator.next();
            boolean portal = entry.getKey() instanceof DhPortalLodViews.PortalPath;
            History history = entry.getValue();
            if (history.users == 0 && (portal ? portals > MAX_VIEWS : mains > MAX_VIEWS && !entry.getKey().equals(mainPath))) {
                iterator.remove();
                retire(history);
                if (portal) {
                    portals--;
                } else {
                    mains--;
                }
            }
        }
    }

    private static void retire(History history) {
        history.retired = true;
        if (history.users == 0) {
            release(history);
        }
    }

    private static void release(History history) {
        try {
            history.close();
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to release Distant Horizons camera temporal textures", failure);
        }
    }

    private static void closeTexture(BlazeTextureWrapper wrapper) {
        GpuTextureView view = wrapper.getTextureView();
        GpuTexture texture = wrapper.getTexture();
        GpuSampler sampler = wrapper.getTextureSampler();
        try {
            if (view != null) {
                view.close();
            }
        } finally {
            try {
                if (texture != null) {
                    texture.close();
                }
            } finally {
                if (sampler != null) {
                    sampler.close();
                }
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Scope parent = active;
        private final DhPortalBlazeTemporalAccess access;
        private final History history;
        private final State previous;
        private boolean closed;

        private Scope(DhPortalBlazeTemporalAccess access, History history) {
            this.access = access;
            this.history = history;
            previous = State.capture(access);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (active != this) {
                throw new IllegalStateException("Distant Horizons temporal scopes must close in order");
            }
            closed = true;
            try {
                history.state = State.capture(access);
            } finally {
                try {
                    previous.apply(access);
                } finally {
                    active = parent;
                    history.users--;
                    if (history.retired && history.users == 0) {
                        release(history);
                    }
                }
            }
        }
    }

    private record State(BlazeTextureWrapper colorA, BlazeTextureWrapper colorB, BlazeTextureWrapper depth, boolean historyA,
                         DhMat4f previousMatrix, DhVec3d previousCamera) {
        private static State capture(DhPortalBlazeTemporalAccess access) {
            return new State(access.wormholes$colorA(), access.wormholes$colorB(), access.wormholes$depth(), access.wormholes$historyA(),
                access.wormholes$previousMatrix(), access.wormholes$previousCamera());
        }

        private void apply(DhPortalBlazeTemporalAccess access) {
            access.wormholes$colorA(colorA);
            access.wormholes$colorB(colorB);
            access.wormholes$depth(depth);
            access.wormholes$historyA(historyA);
            access.wormholes$previousMatrix(previousMatrix);
            access.wormholes$previousCamera(previousCamera);
        }
    }

    private static final class History implements AutoCloseable {
        private final IDhClientWorld world;
        private final IDhClientLevel level;
        private State state;
        private long lastUsed;
        private int users;
        private boolean retired;
        private boolean closed;

        private History(RenderParams parameters) {
            world = parameters.dhClientWorld;
            level = parameters.dhClientLevel;
            state = new State(BlazeTextureWrapper.createColor("Wormholes DH temporal color A"),
                BlazeTextureWrapper.createColor("Wormholes DH temporal color B"),
                BlazeTextureWrapper.createDepth("Wormholes DH temporal depth"), true, new DhMat4f(), new DhVec3d(0.0D, 0.0D, 0.0D));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                closeTexture(state.colorA());
            } finally {
                try {
                    closeTexture(state.colorB());
                } finally {
                    closeTexture(state.depth());
                }
            }
        }
    }
}
