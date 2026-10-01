package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.render.view.ProjectionEntityView;

final class EntityRenderMetadataBridge {
    private static final ProjectedEntityMetadata<EntityData<?>> METADATA = new ProjectedEntityMetadata<>(new MetadataAccess());
    static final long METADATA_BRIDGE_RETRY_MILLIS = 60_000L;

    private final EntityRenderPacketChannel channel;
    private final EntityRenderMapBridge mapBridge;
    private long metadataBridgeRetryAtMillis;

    EntityRenderMetadataBridge(EntityRenderPacketChannel channel) {
        this.channel = channel;
        this.mapBridge = new EntityRenderMapBridge(channel);
        this.metadataBridgeRetryAtMillis = 0L;
    }

    void sendEntityState(Player observer,
                         Entity entity,
                         EntityRenderSpoofedEntity state,
                         int metadataTransform,
                         boolean force) {
        long now = System.currentTimeMillis();
        EntityRenderCaches.sweepStaticCaches(now);
        EntityRenderCaches.EntityStateSnapshot snapshot = entityStateSnapshot(entity, now);
        sendEntityMetadata(observer, entity, state, snapshot, metadataTransform, force,
            metadataBridgeAvailable(metadataBridgeRetryAtMillis, now));
        sendEntityEquipment(observer, state, snapshot, force);
    }

    void sendRemoteEntityState(Player observer,
                               ProjectionEntityView remoteView,
                               EntityVisual visual,
                               EntityRenderSpoofedEntity state,
                               int metadataTransform,
                               boolean force) {
        List<EntityData<?>> metadata = remoteView.getMetadata(visual.id());
        if (metadata != null && !metadata.isEmpty()) {
            Integer sourceMapId = BukkitItemFrameMetadata.TRANSFORM.mapId(metadata);
            ProjectedEntityMaps.Projection mapProjection = mapBridge.projectVisual(
                observer, remoteView, visual, state, metadataTransform, sourceMapId, force);
            metadata = BukkitItemFrameMetadata.TRANSFORM.transformMetadata(
                metadata, metadataTransform, mapProjection.mapId(), mapProjection.stripMapId());
            List<EntityData<?>> patched = state.upsideDown ? withUpsideDownMetadataRemote(visual.isPlayer(), metadata) : metadata;
            String signature = metadataSignature(patched);
            if (force || !signature.equals(state.lastMetadataSignature)) {
                state.lastMetadataSignature = signature;
                channel.send(observer, new WrapperPlayServerEntityMetadata(state.fakeId, patched));
            }
        }
        List<Equipment> equipment = remoteView.getEquipment(visual.id());
        if (equipment != null && !equipment.isEmpty()) {
            String signature = equipmentSignature(equipment);
            if (force || !signature.equals(state.lastEquipmentSignature)) {
                state.lastEquipmentSignature = signature;
                channel.send(observer, new WrapperPlayServerEntityEquipment(state.fakeId, equipment));
            }
        }
    }

    private EntityRenderCaches.EntityStateSnapshot entityStateSnapshot(Entity entity, long now) {
        UUID entityId = entity.getUniqueId();
        EntityRenderCaches.EntityStateSnapshot cached = EntityRenderCaches.freshEntityState(entityId, now);
        if (cached != null) {
            return cached;
        }
        List<EntityData<?>> metadata = List.of();
        String metadataSig = "";
        if (metadataBridgeAvailable(metadataBridgeRetryAtMillis, now)) {
            try {
                metadata = SpigotConversionUtil.getEntityMetadata(entity);
                metadataSig = metadataSignature(metadata);
                if (metadataBridgeRetryAtMillis != 0L) {
                    metadataBridgeRetryAtMillis = 0L;
                }
            } catch (RuntimeException ex) {
                metadataBridgeRetryAtMillis = now + METADATA_BRIDGE_RETRY_MILLIS;
                metadata = List.of();
                metadataSig = "";
                reportMetadataBridgeFailure(entity, ex);
            }
        }
        List<Equipment> equipment = PacketBlobs.collectEquipment(entity);
        String equipmentSig = equipment.isEmpty() ? "" : equipmentSignature(equipment);
        EntityRenderCaches.EntityStateSnapshot next = new EntityRenderCaches.EntityStateSnapshot(now, metadata, metadataSig, equipment, equipmentSig);
        EntityRenderCaches.putEntityState(entityId, next);
        return next;
    }

    static boolean metadataBridgeAvailable(long retryAtMillis, long now) {
        return retryAtMillis == 0L || now >= retryAtMillis;
    }

    private static void reportMetadataBridgeFailure(Entity entity, RuntimeException error) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null) {
            return;
        }
        plugin.getLogger().log(Level.WARNING, "[ProjectedEntityRenderer] entity metadata bridge failed for "
            + entity.getType() + " " + entity.getUniqueId() + "; retrying in "
            + (METADATA_BRIDGE_RETRY_MILLIS / 1000L) + "s", error);
    }

    private void sendEntityMetadata(Player observer,
                                    Entity entity,
                                    EntityRenderSpoofedEntity state,
                                    EntityRenderCaches.EntityStateSnapshot snapshot,
                                    int metadataTransform,
                                    boolean force,
                                    boolean bridgeAvailable) {
        if (!bridgeAvailable) {
            return;
        }
        Integer sourceMapId = BukkitItemFrameMetadata.TRANSFORM.mapId(snapshot.metadata);
        ProjectedEntityMaps.Projection mapProjection = mapBridge.projectLocal(
            observer, entity, state, metadataTransform, sourceMapId, force);
        List<EntityData<?>> metadata = BukkitItemFrameMetadata.TRANSFORM.transformMetadata(
            snapshot.metadata, metadataTransform, mapProjection.mapId(), mapProjection.stripMapId());
        boolean metadataUnchanged = metadataTransform == ProjectedItemFrameTransform.NONE
            && mapProjection.mapId() == null
            && !mapProjection.stripMapId();
        String signature = metadataUnchanged ? snapshot.metadataSig : metadataSignature(metadata);
        if (state.upsideDown) {
            metadata = withUpsideDownMetadata(entity, metadata);
            signature = metadataSignature(metadata);
        }
        if (metadata.isEmpty()) {
            return;
        }
        if (!force && signature.equals(state.lastMetadataSignature)) {
            return;
        }
        state.lastMetadataSignature = signature;
        channel.send(observer, new WrapperPlayServerEntityMetadata(state.fakeId, metadata));
    }

    private void sendEntityEquipment(Player observer, EntityRenderSpoofedEntity state, EntityRenderCaches.EntityStateSnapshot snapshot, boolean force) {
        if (snapshot.equipment.isEmpty()) {
            return;
        }
        if (!force && snapshot.equipmentSig.equals(state.lastEquipmentSignature)) {
            return;
        }
        state.lastEquipmentSignature = snapshot.equipmentSig;
        channel.send(observer, new WrapperPlayServerEntityEquipment(state.fakeId, snapshot.equipment));
    }

    private List<EntityData<?>> withUpsideDownMetadata(Entity entity, List<EntityData<?>> metadata) {
        if (entity instanceof Player) {
            return withUpsideDownPlayerMetadata(metadata);
        }
        if (!(entity instanceof LivingEntity)) {
            return metadata;
        }
        return METADATA.upsideDownEntity(metadata, ProjectedPlayerNames.isFlipName(entity.getCustomName()));
    }

    private static List<EntityData<?>> withUpsideDownMetadataRemote(boolean isPlayer, List<EntityData<?>> metadata) {
        return isPlayer ? withUpsideDownPlayerMetadata(metadata) : METADATA.upsideDownEntity(metadata, false);
    }

    static List<EntityData<?>> upsideDown(boolean player, List<EntityData<?>> metadata) {
        return withUpsideDownMetadataRemote(player, metadata);
    }

    static List<EntityData<?>> withUpsideDownPlayerMetadata(List<EntityData<?>> metadata) {
        return METADATA.upsideDownPlayer(metadata);
    }

    private static String metadataSignature(List<EntityData<?>> metadata) {
        return METADATA.signature(metadata);
    }

    private static String equipmentSignature(List<Equipment> equipment) {
        StringBuilder builder = new StringBuilder(equipment.size() * 8);
        for (Equipment item : equipment) {
            builder.append(item.getSlot()).append('=').append(String.valueOf(item.getItem())).append(';');
        }
        return builder.toString();
    }
    private static final class MetadataAccess implements ProjectedEntityMetadata.Access<EntityData<?>> {
        public int index(EntityData<?> value) { return value.getIndex(); }
        public Object value(EntityData<?> value) { return value.getValue(); }
        public EntityData<?> skinParts(int index, byte parts) { return new EntityData<>(index, EntityDataTypes.BYTE, parts); }
        public EntityData<?> customName(int index, String name) { return new EntityData<>(index, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(Component.text(name))); }
        public EntityData<?> nameVisible(int index, boolean visible) { return new EntityData<>(index, EntityDataTypes.BOOLEAN, visible); }
    }
}
