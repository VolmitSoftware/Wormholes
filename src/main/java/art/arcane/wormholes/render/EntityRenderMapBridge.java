package art.arcane.wormholes.render;

import art.arcane.wormholes.network.view.BukkitProjectedMapData;
import java.util.Optional;
import java.util.UUID;
import art.arcane.wormholes.render.ProjectedEntityMaps.Projection;
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
    private final ProjectedEntityMaps<Player> projected;

    EntityRenderMapBridge(EntityRenderPacketChannel channel) {
        this.projected = new ProjectedEntityMaps<>(new ProjectedEntityMaps.Host<>() {
            @Override
            public void send(Player observer, ProjectedMapData map, int virtualMapId) {
                channel.send(observer, BukkitProjectedMapData.toPacket(map, virtualMapId));
            }

            @Override
            public void invalid(UUID sourceId, String reason, RuntimeException error) {
                reportInvalidPayload(sourceId, reason, error);
            }
        });
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
        Optional<ProjectedMapData> captured = BukkitProjectedMapData.capture(mapView);
        if (captured.isEmpty()) {
            sendMap(observer, mapView);
            return Projection.none();
        }
        return projected.send(observer, state, captured.orElseThrow(), true, force);
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
            Optional<ProjectedMapData> localCapture = BukkitProjectedMapData.capture(localMapView);
            if (localCapture.isPresent()) {
                return projected.send(observer, state, localCapture.orElseThrow(), true, force);
            }
            sendMap(observer, localMapView);
            return Projection.none();
        }
        return projected.project(observer, visual, state, new ProjectedEntityMaps.Options(sourceMapId, metadataTransform, force));
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

    private static void reportInvalidPayload(UUID sourceId,
                                             String reason,
                                             RuntimeException error) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null) {
            return;
        }
        String message = "[ProjectedEntityRenderer] rejected projected map data for " + sourceId + ": " + reason;
        if (error == null) {
            plugin.getLogger().warning(message);
            return;
        }
        plugin.getLogger().log(Level.WARNING, message, error);
    }

}
