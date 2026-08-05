package art.arcane.wormholes.render;

import java.util.Optional;
import java.util.logging.Level;

import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.ProjectedMapData;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.service.WormholesTelemetry;

final class EntityRenderMapBridge {
    private final EntityRenderPacketChannel channel;

    EntityRenderMapBridge(EntityRenderPacketChannel channel) {
        this.channel = channel;
    }

    Projection projectLocal(Player observer,
                            Entity entity,
                            EntityRenderSpoofedEntity state,
                            int metadataTransform,
                            Integer sourceMapId,
                            boolean force) {
        if (sourceMapId == null || !(entity instanceof ItemFrame itemFrame)) {
            return Projection.none();
        }
        MapView mapView = mapView(itemFrame);
        if (mapView == null) {
            return Projection.none();
        }
        if (!ProjectedItemFrameTransform.isReversed(metadataTransform)) {
            sendMap(observer, mapView);
            return Projection.none();
        }
        Optional<ProjectedMapData> captured = ProjectedMapData.capture(mapView);
        if (captured.isEmpty()) {
            sendMap(observer, mapView);
            return Projection.none();
        }
        return sendProjected(observer, state, captured.orElseThrow(), true, force);
    }

    Projection projectVisual(Player observer,
                             ProjectionEntityView entityView,
                             EntityVisual visual,
                             EntityRenderSpoofedEntity state,
                             int metadataTransform,
                             Integer sourceMapId,
                             boolean force) {
        if (sourceMapId == null) {
            return Projection.none();
        }
        MapView localMapView = entityView.getMapView(visual.id());
        boolean reversed = ProjectedItemFrameTransform.isReversed(metadataTransform);
        if (localMapView != null && !reversed) {
            sendMap(observer, localMapView);
            return Projection.none();
        }
        if (localMapView != null) {
            Optional<ProjectedMapData> localCapture = ProjectedMapData.capture(localMapView);
            if (localCapture.isPresent()) {
                return sendProjected(observer, state, localCapture.orElseThrow(), true, force);
            }
            sendMap(observer, localMapView);
            return Projection.none();
        }
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
            return sendProjected(observer, state, mapData, reversed, force);
        } catch (IllegalArgumentException error) {
            reportInvalidPayload(visual, state, "payload did not decode", error);
            return Projection.strip();
        }
    }

    private Projection sendProjected(Player observer,
                                     EntityRenderSpoofedEntity state,
                                     ProjectedMapData source,
                                     boolean reversed,
                                     boolean force) {
        int virtualMapId = virtualMapId(state.fakeId);
        boolean mapChanged = state.updateMapData(source, reversed);
        if (force || mapChanged) {
            ProjectedMapData projected = reversed ? source.mirrorHorizontally() : source;
            channel.send(observer, projected.toPacket(virtualMapId));
        }
        return Projection.virtual(virtualMapId);
    }

    private static int virtualMapId(int fakeEntityId) {
        return fakeEntityId > 0 ? -fakeEntityId : Integer.MIN_VALUE + Math.floorMod(fakeEntityId, Integer.MAX_VALUE);
    }

    private static MapView mapView(ItemFrame itemFrame) {
        ItemStack item = itemFrame.getItem();
        if (item == null) {
            return null;
        }
        ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof MapMeta mapMeta) || !mapMeta.hasMapView()) {
            return null;
        }
        return mapMeta.getMapView();
    }

    private static void sendMap(Player observer, MapView mapView) {
        WormholesTelemetry.countPacket();
        observer.sendMap(mapView);
    }

    private static void reportInvalidPayload(EntityVisual visual,
                                             EntityRenderSpoofedEntity state,
                                             String reason,
                                             RuntimeException error) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null || !state.markMapPayloadFailureReported()) {
            return;
        }
        String message = "[ProjectedEntityRenderer] rejected projected map data for " + visual.id() + ": " + reason;
        if (error == null) {
            plugin.getLogger().warning(message);
            return;
        }
        plugin.getLogger().log(Level.WARNING, message, error);
    }

    record Projection(Integer mapId, boolean stripMapId) {
        private static Projection none() {
            return new Projection(null, false);
        }

        private static Projection virtual(int mapId) {
            return new Projection(Integer.valueOf(mapId), false);
        }

        private static Projection strip() {
            return new Projection(null, true);
        }
    }
}
