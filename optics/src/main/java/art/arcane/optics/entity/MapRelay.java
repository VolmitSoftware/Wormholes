package art.arcane.optics.entity;

public final class MapRelay<O> {
    private final EntityOutput<O, ?, ?, ?, ?> output;

    public MapRelay(EntityOutput<O, ?, ?, ?, ?> output) {
        this.output = output;
    }

    public Projection project(O observer, EntitySnapshot visual, SpoofedEntity state, Options options) {
        Integer sourceMapId = options.sourceMapId();
        if (sourceMapId == null) {
            return Projection.none();
        }
        boolean reversed = ItemFrameTransform.isReversed(options.metadataTransform());
        boolean force = options.force();
        byte[] encoded = visual.mapData();
        if (encoded == null || encoded.length == 0) {
            return Projection.strip();
        }
        try {
            MapSnapshot mapData = MapSnapshot.decode(encoded);
            if (mapData.sourceMapId() != sourceMapId.intValue()) {
                reportInvalidPayload(observer, visual, state, "source map id does not match item metadata", null);
                return Projection.strip();
            }
            return send(observer, state, mapData, reversed, force);
        } catch (IllegalArgumentException error) {
            reportInvalidPayload(observer, visual, state, "payload did not decode", error);
            return Projection.strip();
        }
    }

    public Projection send(O observer,
                                     SpoofedEntity state,
                                     MapSnapshot source,
                                     boolean reversed,
                                     boolean force) {
        int virtualMapId = virtualMapId(state.fakeId());
        boolean mapChanged = state.updateMapData(source, reversed);
        if (force || mapChanged) {
            MapSnapshot projected = reversed ? source.mirrorHorizontally() : source;
            output.map(observer, projected, virtualMapId);
        }
        return Projection.virtual(virtualMapId);
    }

    private static int virtualMapId(int fakeEntityId) {
        return fakeEntityId > 0 ? -fakeEntityId : Integer.MIN_VALUE + Math.floorMod(fakeEntityId, Integer.MAX_VALUE);
    }

    private void reportInvalidPayload(O observer, EntitySnapshot visual, SpoofedEntity state, String reason, RuntimeException error) {
        if (state.markMapPayloadFailureReported()) {
            output.warning(observer, "rejected projected map data for " + visual.id() + ": " + reason, error);
        }
    }

    public record Options(Integer sourceMapId, int metadataTransform, boolean force) {
    }

    public record Projection(Integer mapId, boolean stripMapId) {
        public static Projection none() { return new Projection(null, false); }
        public static Projection virtual(int mapId) { return new Projection(mapId, false); }
        public static Projection strip() { return new Projection(null, true); }
    }
}
