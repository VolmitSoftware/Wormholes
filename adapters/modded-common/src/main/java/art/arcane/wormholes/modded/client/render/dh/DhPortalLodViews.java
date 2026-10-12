package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.IDataSourceUpdateListenerFunc;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.ClientLevelModule;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.world.IDhClientWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class DhPortalLodViews {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long ACTIVE_MILLIS = 1000L;
    private static final long EXPIRE_MILLIS = 60000L;
    private static final int MAX_VIEWS = 12;
    private static final Map<Path, Selection> SELECTIONS = new ConcurrentHashMap<>();

    private DhPortalLodViews() {
    }

    static MainPath mainPath(ClientLevel destination) {
        return new MainPath(destination.dimension().identifier().toString());
    }

    static PortalPath path(PortalPath parent, PortalView view) {
        List<Camera> views = new ArrayList<>(parent == null ? 1 : parent.views().size() + 1);
        if (parent != null) {
            views.addAll(parent.views());
        }
        views.add(camera(view));
        return new PortalPath(List.copyOf(views));
    }

    static Selection acquire(Path path, RenderParams parameters, Vec3d camera) {
        Selection selection = SELECTIONS.compute(path, (key, previous) -> {
            Selection current = previous;
            if (current == null || current.world != parameters.dhClientWorld || current.level != parameters.dhClientLevel) {
                if (current != null) {
                    release(current);
                }
                current = new Selection(parameters.dhClientWorld, parameters.dhClientLevel);
            }
            current.camera = camera;
            current.lastUsed = System.currentTimeMillis();
            current.interested = true;
            current.users.incrementAndGet();
            return current;
        });
        trim();
        return selection;
    }

    static void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Path, Selection> entry : SELECTIONS.entrySet()) {
            Selection selection = entry.getValue();
            long idle = now - selection.lastUsed;
            if (idle >= EXPIRE_MILLIS && selection.users.get() == 0) {
                removeUnused(entry.getKey(), selection, now, true);
                continue;
            }
            if (!selection.interested || idle > ACTIVE_MILLIS) {
                continue;
            }
            try {
                selection.tick();
            } catch (RuntimeException failure) {
                if (SELECTIONS.remove(entry.getKey(), selection)) {
                    release(selection);
                }
                LOGGER.error("Unable to prepare Distant Horizons terrain for a portal view", failure);
            }
        }
        trim();
    }

    static void closeView(PortalView view) {
        if (SELECTIONS.isEmpty()) {
            return;
        }
        Camera camera = camera(view);
        for (Map.Entry<Path, Selection> entry : SELECTIONS.entrySet()) {
            if (entry.getKey() instanceof PortalPath path && path.views().contains(camera)) {
                entry.getValue().interested = false;
            }
        }
    }

    static void closeProvider(Object provider) {
        for (Map.Entry<Path, Selection> entry : SELECTIONS.entrySet()) {
            if (entry.getValue().provider == provider && SELECTIONS.remove(entry.getKey(), entry.getValue())) {
                release(entry.getValue());
            }
        }
    }

    static void clear() {
        for (Map.Entry<Path, Selection> entry : SELECTIONS.entrySet()) {
            if (SELECTIONS.remove(entry.getKey(), entry.getValue())) {
                release(entry.getValue());
            }
        }
    }

    private static Camera camera(PortalView view) {
        return new Camera(view.kind(), view.historyKey(), view.source().dimension().identifier().toString(),
            view.destination().dimension().identifier().toString(), view.toDestination());
    }

    private static void trim() {
        while (SELECTIONS.size() > MAX_VIEWS) {
            Map.Entry<Path, Selection> oldest = null;
            for (Map.Entry<Path, Selection> entry : SELECTIONS.entrySet()) {
                Selection selection = entry.getValue();
                if (selection.users.get() == 0 && (oldest == null || selection.lastUsed < oldest.getValue().lastUsed)) {
                    oldest = entry;
                }
            }
            if (oldest == null) {
                return;
            }
            removeUnused(oldest.getKey(), oldest.getValue(), System.currentTimeMillis(), false);
        }
    }

    private static void removeUnused(Path path, Selection selection, long now, boolean expired) {
        AtomicBoolean removed = new AtomicBoolean();
        SELECTIONS.computeIfPresent(path, (key, current) -> {
            if (current != selection || current.users.get() != 0 || (expired && now - current.lastUsed < EXPIRE_MILLIS)) {
                return current;
            }
            removed.set(true);
            return null;
        });
        if (removed.get()) {
            release(selection);
        }
    }

    private static void release(Selection selection) {
        try {
            selection.close();
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to release Distant Horizons terrain for a portal view", failure);
        }
    }

    sealed interface Path permits MainPath, PortalPath {
    }

    record MainPath(String dimension) implements Path {
    }

    record PortalPath(List<Camera> views) implements Path {
    }

    private record Camera(PortalView.Kind kind, Object key, String source, String destination, Similarity transform) {
    }

    static final class Selection implements IDataSourceUpdateListenerFunc<FullDataSourceV2> {
        private final IDhClientWorld world;
        private final IDhClientLevel level;
        private final FullDataSourceProviderV2 provider;
        private final AtomicInteger users = new AtomicInteger();
        private final AtomicBoolean ticking = new AtomicBoolean();
        private volatile Vec3d camera;
        private volatile long lastUsed = System.currentTimeMillis();
        private volatile RenderBufferHandler buffers;
        private volatile LodQuadTree tree;
        private volatile boolean closed;
        private volatile boolean interested = true;

        private Selection(IDhClientWorld world, IDhClientLevel level) {
            this.world = world;
            this.level = level;
            provider = level.getFullDataProvider();
            provider.addDataSourceUpdateListener(this);
        }

        @Override
        public void OnDataSourceUpdated(FullDataSourceV2 source) {
            LodQuadTree current = tree;
            if (current != null && !closed) {
                current.queuePosToReload(source.getPos());
            }
        }

        RenderBufferHandler buffers() {
            return buffers;
        }

        void release() {
            users.decrementAndGet();
        }

        private void tick() {
            if (!ticking.compareAndSet(false, true)) {
                return;
            }
            try {
                prepare();
            } finally {
                ticking.set(false);
            }
        }

        private void prepare() {
            Vec3d position = camera;
            if (closed || position == null) {
                return;
            }
            LodQuadTree current = tree;
            int diameter = ClientLevelModule.ClientRenderState.getViewDiameterInBlocks();
            if (current == null || current.blockRenderDistanceDiameter != diameter) {
                LodQuadTree replacement = new LodQuadTree(level, diameter, (int) Math.floor(position.x()),
                    (int) Math.floor(position.z()), provider);
                RenderBufferHandler replacementBuffers;
                try {
                    replacementBuffers = new RenderBufferHandler(replacement);
                } catch (RuntimeException | Error failure) {
                    try {
                        replacement.close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
                synchronized (this) {
                    if (closed) {
                        replacement.close();
                        return;
                    }
                    tree = replacement;
                    buffers = replacementBuffers;
                }
                if (current != null) {
                    current.close();
                }
                current = replacement;
            }
            if (!closed) {
                current.tryTick(new DhBlockPos2D((int) Math.floor(position.x()), (int) Math.floor(position.z())));
            }
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            LodQuadTree current = tree;
            tree = null;
            buffers = null;
            try {
                provider.removeDataSourceUpdateListener(this);
            } finally {
                if (current != null) {
                    current.close();
                }
            }
        }
    }
}
