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
    private static final int CUSTOM_NAME_INDEX = 2;
    private static final int CUSTOM_NAME_VISIBLE_INDEX = 3;
    private static final int PLAYER_SKIN_PARTS_INDEX = 17;
    private static final byte CAPE_PART_BIT = 0x01;
    static final long METADATA_BRIDGE_RETRY_MILLIS = 60_000L;

    private final EntityRenderPacketChannel channel;
    private long metadataBridgeRetryAtMillis;

    EntityRenderMetadataBridge(EntityRenderPacketChannel channel) {
        this.channel = channel;
        this.metadataBridgeRetryAtMillis = 0L;
    }

    void sendEntityState(Player observer, Entity entity, EntityRenderSpoofedEntity state, boolean force) {
        long now = System.currentTimeMillis();
        EntityRenderCaches.sweepStaticCaches(now);
        EntityRenderCaches.EntityStateSnapshot snapshot = entityStateSnapshot(entity, now);
        sendEntityMetadata(observer, entity, state, snapshot, force,
            metadataBridgeAvailable(metadataBridgeRetryAtMillis, now));
        sendEntityEquipment(observer, state, snapshot, force);
    }

    void sendRemoteEntityState(Player observer, ProjectionEntityView remoteView, EntityVisual visual, EntityRenderSpoofedEntity state) {
        List<EntityData<?>> metadata = remoteView.getMetadata(visual.id());
        if (metadata != null && !metadata.isEmpty()) {
            List<EntityData<?>> patched = state.upsideDown ? withUpsideDownMetadataRemote(visual.isPlayer(), metadata) : metadata;
            channel.send(observer, new WrapperPlayServerEntityMetadata(state.fakeId, patched));
        }
        List<Equipment> equipment = remoteView.getEquipment(visual.id());
        if (equipment != null && !equipment.isEmpty()) {
            channel.send(observer, new WrapperPlayServerEntityEquipment(state.fakeId, equipment));
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

    private void sendEntityMetadata(Player observer, Entity entity, EntityRenderSpoofedEntity state, EntityRenderCaches.EntityStateSnapshot snapshot, boolean force, boolean bridgeAvailable) {
        if (!bridgeAvailable) {
            return;
        }
        List<EntityData<?>> metadata = snapshot.metadata;
        String signature = snapshot.metadataSig;
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
            List<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size() + 1);
            byte skinParts = 0;
            for (EntityData<?> data : metadata) {
                if (data.getIndex() == PLAYER_SKIN_PARTS_INDEX && data.getValue() instanceof Byte) {
                    skinParts = ((Byte) data.getValue()).byteValue();
                    continue;
                }
                patched.add(data);
            }
            patched.add(new EntityData<Byte>(PLAYER_SKIN_PARTS_INDEX, EntityDataTypes.BYTE, Byte.valueOf((byte) (skinParts | CAPE_PART_BIT))));
            return patched;
        }
        if (!(entity instanceof LivingEntity)) {
            return metadata;
        }
        List<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size() + 2);
        for (EntityData<?> data : metadata) {
            if (data.getIndex() == CUSTOM_NAME_INDEX || data.getIndex() == CUSTOM_NAME_VISIBLE_INDEX) {
                continue;
            }
            patched.add(data);
        }
        if (!ProjectedEntityRenderer.isFlipName(entity.getCustomName())) {
            patched.add(new EntityData<Optional<Component>>(CUSTOM_NAME_INDEX, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(Component.text(ProjectedEntityRenderer.FLIP_NAME))));
            patched.add(new EntityData<Boolean>(CUSTOM_NAME_VISIBLE_INDEX, EntityDataTypes.BOOLEAN, Boolean.FALSE));
        }
        return patched;
    }

    private List<EntityData<?>> withUpsideDownMetadataRemote(boolean isPlayer, List<EntityData<?>> metadata) {
        if (isPlayer) {
            List<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size() + 1);
            byte skinParts = 0;
            for (EntityData<?> data : metadata) {
                if (data.getIndex() == PLAYER_SKIN_PARTS_INDEX && data.getValue() instanceof Byte) {
                    skinParts = ((Byte) data.getValue()).byteValue();
                    continue;
                }
                patched.add(data);
            }
            patched.add(new EntityData<Byte>(PLAYER_SKIN_PARTS_INDEX, EntityDataTypes.BYTE, Byte.valueOf((byte) (skinParts | CAPE_PART_BIT))));
            return patched;
        }
        List<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size() + 2);
        for (EntityData<?> data : metadata) {
            if (data.getIndex() == CUSTOM_NAME_INDEX || data.getIndex() == CUSTOM_NAME_VISIBLE_INDEX) {
                continue;
            }
            patched.add(data);
        }
        patched.add(new EntityData<Optional<Component>>(CUSTOM_NAME_INDEX, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(Component.text(ProjectedEntityRenderer.FLIP_NAME))));
        patched.add(new EntityData<Boolean>(CUSTOM_NAME_VISIBLE_INDEX, EntityDataTypes.BOOLEAN, Boolean.FALSE));
        return patched;
    }

    private static String metadataSignature(List<EntityData<?>> metadata) {
        StringBuilder builder = new StringBuilder(metadata.size() * 8);
        for (EntityData<?> data : metadata) {
            builder.append(data.getIndex()).append('=').append(String.valueOf(data.getValue())).append(';');
        }
        return builder.toString();
    }

    private static String equipmentSignature(List<Equipment> equipment) {
        StringBuilder builder = new StringBuilder(equipment.size() * 8);
        for (Equipment item : equipment) {
            builder.append(item.getSlot()).append('=').append(String.valueOf(item.getItem())).append(';');
        }
        return builder.toString();
    }
}
