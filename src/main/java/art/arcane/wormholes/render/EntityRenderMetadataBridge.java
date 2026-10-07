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
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;

import art.arcane.wormholes.Wormholes;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.optics.entity.ItemFrameTransform;
import art.arcane.optics.entity.PlayerNames;
import art.arcane.optics.entity.ProjectedMaps;
import art.arcane.optics.entity.ProjectedMetadata;
import art.arcane.optics.entity.SpoofedEntity;
import art.arcane.optics.entity.EntityOutput;
import art.arcane.optics.entity.ItemFrameMetadata;
import art.arcane.optics.entity.MetadataAccess;
import art.arcane.optics.math.Face;

final class EntityRenderMetadataBridge {
    static final long METADATA_BRIDGE_RETRY_MILLIS = 60_000L;
    private static final MetadataAccess<EntityData<?>> ACCESS = new Access();
    private static final ProjectedMetadata<EntityData<?>> ENTITIES = new ProjectedMetadata<>(ACCESS);
    static final ItemFrameMetadata<EntityData<?>> FRAMES = new ItemFrameMetadata<>(ACCESS);

    private final EntityRenderPacketChannel channel;
    private final EntityRenderMapBridge mapBridge;
    private long metadataBridgeRetryAtMillis;

    EntityRenderMetadataBridge(EntityRenderPacketChannel channel, EntityOutput<Player, ?, ?, ?, ?> output) {
        this.channel = channel;
        this.mapBridge = new EntityRenderMapBridge(output);
        this.metadataBridgeRetryAtMillis = 0L;
    }

    void sendEntityState(Player observer,
                         Entity entity,
                         SpoofedEntity state,
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
                               EntitySnapshot visual,
                               SpoofedEntity state,
                               int metadataTransform,
                               boolean force) {
        List<EntityData<?>> metadata = remoteView.getMetadata(visual.id());
        if (metadata != null && !metadata.isEmpty()) {
            Integer sourceMapId = FRAMES.mapId(metadata);
            ProjectedMaps.Projection mapProjection = mapBridge.projectVisual(
                observer, remoteView, visual, state, metadataTransform, sourceMapId, force);
            metadata = FRAMES.transformMetadata(
                metadata, metadataTransform, mapProjection.mapId(), mapProjection.stripMapId());
            List<EntityData<?>> patched = state.upsideDown() ? withUpsideDownMetadataRemote(visual.isPlayer(), metadata) : metadata;
            String signature = metadataSignature(patched);
            if (force || !signature.equals(state.lastMetadataSignature())) {
                state.setLastMetadataSignature(signature);
                channel.send(observer, new WrapperPlayServerEntityMetadata(state.fakeId(), patched));
            }
        }
        List<Equipment> equipment = remoteView.getEquipment(visual.id());
        if (equipment != null && !equipment.isEmpty()) {
            String signature = equipmentSignature(equipment);
            if (force || !signature.equals(state.lastEquipmentSignature())) {
                state.setLastEquipmentSignature(signature);
                channel.send(observer, new WrapperPlayServerEntityEquipment(state.fakeId(), equipment));
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
                                    SpoofedEntity state,
                                    EntityRenderCaches.EntityStateSnapshot snapshot,
                                    int metadataTransform,
                                    boolean force,
                                    boolean bridgeAvailable) {
        if (!bridgeAvailable) {
            return;
        }
        Integer sourceMapId = FRAMES.mapId(snapshot.metadata);
        ProjectedMaps.Projection mapProjection = mapBridge.projectLocal(
            observer, entity, state, metadataTransform, sourceMapId, force);
        List<EntityData<?>> metadata = FRAMES.transformMetadata(
            snapshot.metadata, metadataTransform, mapProjection.mapId(), mapProjection.stripMapId());
        boolean metadataUnchanged = metadataTransform == ItemFrameTransform.NONE
            && mapProjection.mapId() == null
            && !mapProjection.stripMapId();
        String signature = metadataUnchanged ? snapshot.metadataSig : metadataSignature(metadata);
        if (state.upsideDown()) {
            metadata = withUpsideDownMetadata(entity, metadata);
            signature = metadataSignature(metadata);
        }
        if (metadata.isEmpty()) {
            return;
        }
        if (!force && signature.equals(state.lastMetadataSignature())) {
            return;
        }
        state.setLastMetadataSignature(signature);
        channel.send(observer, new WrapperPlayServerEntityMetadata(state.fakeId(), metadata));
    }

    private void sendEntityEquipment(Player observer, SpoofedEntity state, EntityRenderCaches.EntityStateSnapshot snapshot, boolean force) {
        if (snapshot.equipment.isEmpty()) {
            return;
        }
        if (!force && snapshot.equipmentSig.equals(state.lastEquipmentSignature())) {
            return;
        }
        state.setLastEquipmentSignature(snapshot.equipmentSig);
        channel.send(observer, new WrapperPlayServerEntityEquipment(state.fakeId(), snapshot.equipment));
    }

    private List<EntityData<?>> withUpsideDownMetadata(Entity entity, List<EntityData<?>> metadata) {
        if (entity instanceof Player) {
            return withUpsideDownPlayerMetadata(metadata);
        }
        if (!(entity instanceof LivingEntity)) {
            return metadata;
        }
        return ENTITIES.upsideDownEntity(metadata, PlayerNames.isFlipName(entity.getCustomName()));
    }

    private static List<EntityData<?>> withUpsideDownMetadataRemote(boolean isPlayer, List<EntityData<?>> metadata) {
        return isPlayer ? withUpsideDownPlayerMetadata(metadata) : ENTITIES.upsideDownEntity(metadata, false);
    }

    static List<EntityData<?>> upsideDown(boolean player, List<EntityData<?>> metadata) {
        return withUpsideDownMetadataRemote(player, metadata);
    }

    static List<EntityData<?>> withUpsideDownPlayerMetadata(List<EntityData<?>> metadata) {
        return ENTITIES.upsideDownPlayer(metadata);
    }

    private static String metadataSignature(List<EntityData<?>> metadata) {
        return ENTITIES.signature(metadata);
    }

    private static String equipmentSignature(List<Equipment> equipment) {
        StringBuilder builder = new StringBuilder(equipment.size() * 8);
        for (Equipment item : equipment) {
            builder.append(item.getSlot()).append('=').append(String.valueOf(item.getItem())).append(';');
        }
        return builder.toString();
    }
    private static BlockFace blockFace(Face direction) {
        return switch (direction) {
            case D -> BlockFace.DOWN;
            case U -> BlockFace.UP;
            case N -> BlockFace.NORTH;
            case S -> BlockFace.SOUTH;
            case W -> BlockFace.WEST;
            case E -> BlockFace.EAST;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityData<T> replaceValue(EntityData<?> source, T value) {
        EntityDataType<T> type = (EntityDataType<T>) source.getType();
        return new EntityData<T>(source.getIndex(), type, value);
    }

    private static final class Access implements MetadataAccess<EntityData<?>> {
        @Override
        public int index(EntityData<?> value) { return value.getIndex(); }
        @Override
        public Object value(EntityData<?> value) { return value.getValue(); }
        @Override
        public EntityData<?> replace(EntityData<?> value, Object replacement) { return replaceValue(value, replacement); }
        @Override
        public EntityData<?> skinParts(int index, byte parts) { return new EntityData<>(index, EntityDataTypes.BYTE, parts); }
        @Override
        public EntityData<?> customName(int index, String name) {
            return new EntityData<>(index, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(Component.text(name)));
        }
        @Override
        public EntityData<?> nameVisible(int index, boolean visible) { return new EntityData<>(index, EntityDataTypes.BOOLEAN, visible); }
        @Override
        public boolean isDirection(Object value) { return value instanceof BlockFace; }
        @Override
        public boolean isItem(Object value) { return value instanceof ItemStack; }
        @Override
        public Object direction(Face direction) { return blockFace(direction); }
        @Override
        public Integer mapId(Object item) { return ((ItemStack) item).getComponent(ComponentTypes.MAP_ID).orElse(null); }
        @Override
        public Object withMapId(Object item, Integer id) {
            ItemStack copy = ((ItemStack) item).copy();
            if (id == null) {
                copy.unsetComponent(ComponentTypes.MAP_ID);
            } else {
                copy.setComponent(ComponentTypes.MAP_ID, id);
            }
            return copy;
        }
    }
}
