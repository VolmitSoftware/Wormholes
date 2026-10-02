package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.mixin.MapDataAccess;
import art.arcane.wormholes.modded.mixin.EntityDataAccess;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.ProjectedMapData;
import art.arcane.wormholes.network.view.ViewEntityState;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MinecraftEntityVisualCapture {
    private static final EquipmentSlot[] EQUIPMENT = EquipmentSlot.values();
    private final MinecraftPacketBlobs blobs;

    public MinecraftEntityVisualCapture(MinecraftPacketBlobs blobs) {
        this.blobs = blobs;
    }

    public EntityVisual capture(Entity entity, ViewEntityState<Pose> state, long tick) {
        UUID id = entity.getUUID();
        EntityVisual previous = state.lastCapturedSnapshots().get(id);
        ViewEntityState.BlobCaptureState<Pose> previousBlob = state.blobCaptureStates().get(id);
        int signature = signature(entity);
        long interval = entity instanceof ItemFrame ? 10L : 40L;
        boolean capture = previous == null || previousBlob == null || tick - previousBlob.lastCaptureTick() >= interval
            || previousBlob.pose() != entity.getPose() || previousBlob.onFire() != entity.isOnFire()
            || previousBlob.stateSignature() != signature;
        byte[] metadata = capture ? metadata(entity) : previous.metadata();
        byte[] equipment = capture ? equipment(entity) : previous.equipment();
        byte[] map = capture ? map(entity) : previous.mapData();
        if (capture) {
            state.blobCaptureStates().put(id, new ViewEntityState.BlobCaptureState<>(tick, entity.getPose(), entity.isOnFire(), signature));
        }
        String name = "";
        String texture = "";
        String textureSignature = "";
        if (entity instanceof ServerPlayer player) {
            name = player.getGameProfile().name();
            if (state.sentProfiles().add(id)) {
                for (Property property : player.getGameProfile().properties().get("textures")) {
                    texture = property.value();
                    textureSignature = property.signature() == null ? "" : property.signature();
                    break;
                }
            }
        }
        Vec3 position = entity.position();
        Vec3 velocity = entity.getDeltaMovement();
        Vec3 look = entity instanceof HangingEntity hanging
            ? Vec3.atLowerCornerOf(hanging.getDirection().getUnitVec3i())
            : entity instanceof LivingEntity ? entity.getHeadLookAngle() : entity.getLookAngle();
        Entity vehicle = entity.getVehicle();
        Entity leash = entity instanceof Leashable leashable ? leashable.getLeashHolder() : null;
        EntityVisual visual = EntityVisual.full(id, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
            position.x, position.y, position.z, entity.getBbHeight(), look.x, look.y, look.z,
            entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot(), entity.getXRot(), velocity.x, velocity.y, velocity.z, entity.onGround(),
            name, texture, textureSignature, vehicle == null ? null : vehicle.getUUID(), leash == null ? null : leash.getUUID(),
            metadata, equipment, map, 0);
        state.lastCapturedSnapshots().put(id, visual);
        return visual;
    }

    private byte[] metadata(Entity entity) {
        SynchedEntityData.DataItem<?>[] items = ((EntityDataAccess) entity.getEntityData()).wormholesItems();
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(items.length);
        for (SynchedEntityData.DataItem<?> item : items) {
            values.add(item.value());
        }
        return blobs.writeMetadata(values);
    }

    private byte[] equipment(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return EntityVisual.EMPTY;
        }
        List<MinecraftPacketBlobs.Equipment> items = new ArrayList<>(EQUIPMENT.length);
        for (EquipmentSlot slot : EQUIPMENT) {
            if (living.canUseSlot(slot)) {
                items.add(new MinecraftPacketBlobs.Equipment(slot, living.getItemBySlot(slot)));
            }
        }
        return blobs.writeEquipment(items);
    }

    private static int signature(Entity entity) {
        if (entity instanceof ItemFrame frame) {
            return 31 * ItemStack.hashItemAndComponents(frame.getItem()) + frame.getRotation();
        }
        if (!(entity instanceof LivingEntity living)) {
            return 0;
        }
        int signature = 1;
        for (EquipmentSlot slot : EQUIPMENT) {
            if (living.canUseSlot(slot)) {
                ItemStack item = living.getItemBySlot(slot);
                signature = 31 * signature + 31 * ItemStack.hashItemAndComponents(item) + item.getCount();
            }
        }
        return signature;
    }

    private static byte[] map(Entity entity) {
        if (!(entity instanceof ItemFrame frame)) {
            return EntityVisual.EMPTY;
        }
        ItemStack item = frame.getItem();
        MapId id = item.get(DataComponents.MAP_ID);
        MapItemSavedData data = MapItem.getSavedData(item, entity.level());
        if (id == null || data == null) {
            return EntityVisual.EMPTY;
        }
        return new ProjectedMapData(id.id(), data.scale, ((MapDataAccess) data).wormholesTrackingPosition(),
            data.locked, data.colors).encode();
    }
}
