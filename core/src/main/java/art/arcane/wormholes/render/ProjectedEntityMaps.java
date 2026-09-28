package art.arcane.wormholes.render;

import java.util.UUID;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.ProjectedMapData;

public final class ProjectedEntityMaps<O> {
    private final Host<O> host;

    public ProjectedEntityMaps(Host<O> host) {
        this.host = host;
    }

    public Projection project(O observer, EntityVisual visual, EntityRenderSpoofedEntity state, Options options) {
        Integer sourceMapId = options.sourceMapId();
        if (sourceMapId == null) {
            return Projection.none();
        }
        boolean reversed = ProjectedItemFrameTransform.isReversed(options.metadataTransform());
        boolean force = options.force();
        byte[] encoded = visual.mapData();
        if (encoded == null || encoded.length == 0) {
            return Projection.strip();
        }
        try {
            ProjectedMapData mapData = ProjectedMapData.decode(encoded);
            if (mapData.sourceMapId() != sourceMapId.intValue()) {
                reportInvalidPayload(visual, state, "source map id does not match item metadata", null);
                return Projection.strip();
            }
            return send(observer, state, mapData, reversed, force);
        } catch (IllegalArgumentException error) {
            reportInvalidPayload(visual, state, "payload did not decode", error);
            return Projection.strip();
        }
    }

    public Projection send(O observer,
                                     EntityRenderSpoofedEntity state,
                                     ProjectedMapData source,
                                     boolean reversed,
                                     boolean force) {
        int virtualMapId = virtualMapId(state.fakeId);
        boolean mapChanged = state.updateMapData(source, reversed);
        if (force || mapChanged) {
            ProjectedMapData projected = reversed ? source.mirrorHorizontally() : source;
            host.send(observer, projected, virtualMapId);
        }
        return Projection.virtual(virtualMapId);
    }

    private static int virtualMapId(int fakeEntityId) {
        return fakeEntityId > 0 ? -fakeEntityId : Integer.MIN_VALUE + Math.floorMod(fakeEntityId, Integer.MAX_VALUE);
    }

    private void reportInvalidPayload(EntityVisual visual, EntityRenderSpoofedEntity state, String reason, RuntimeException error) {
        if (state.markMapPayloadFailureReported()) {
            host.invalid(visual.id(), reason, error);
        }
    }

    public record Options(Integer sourceMapId, int metadataTransform, boolean force) {
    }

    public interface Host<O> {
        void send(O observer, ProjectedMapData map, int virtualMapId);
        void invalid(UUID sourceId, String reason, RuntimeException error);
    }

    public record Projection(Integer mapId, boolean stripMapId) {
        public static Projection none() { return new Projection(null, false); }
        public static Projection virtual(int mapId) { return new Projection(mapId, false); }
        public static Projection strip() { return new Projection(null, true); }
    }
}
