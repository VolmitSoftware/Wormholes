package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.view.ProjectedMapData;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.RemoteViewCache.RemoteProfile;
import art.arcane.wormholes.render.EntityRenderSpoofedEntity;
import art.arcane.wormholes.render.EntityRenderVisualProjector;
import art.arcane.wormholes.render.ProjectedEntityMaps;
import art.arcane.wormholes.render.ProjectedPlayerNames;
import art.arcane.wormholes.render.view.ProjectionEntityData;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MinecraftEntityVisualHost implements EntityRenderVisualProjector.Host<ServerPlayer, Vec3, EntityType<?>,
    ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment>>, ProjectedEntityMaps.Host<ServerPlayer> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<EntityType<?>, Boolean> LIVING = new HashMap<>();
    private final ServerPlayer observer;
    private final MinecraftEntityPackets packets;
    private final MinecraftPacketBlobs blobs;
    private final ProjectedEntityMaps<ServerPlayer> maps = new ProjectedEntityMaps<>(this);

    public MinecraftEntityVisualHost(ServerPlayer observer, MinecraftEntityPackets packets) {
        this.observer = observer;
        this.packets = packets;
        this.blobs = new MinecraftPacketBlobs(observer.level().registryAccess());
    }

    @Override
    public EntityType<?> packetType(String key) {
        Identifier id = key == null ? null : Identifier.tryParse(key);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    @Override
    public List<EntityVisual> entities(ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view,
                                      EntityRenderVisualProjector.EntityRange range) {
        return view.getEntities(range.x(), range.y(), range.z(), range.range());
    }

    @Override
    public boolean visible(ServerPlayer observer, ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view, UUID entityId) {
        return !(view instanceof MinecraftLocalEntityView local) || local.visible(observer, entityId);
    }

    @Override
    public boolean isItemFrame(EntityType<?> type) {
        return type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME;
    }

    @Override
    public boolean isHanging(EntityType<?> type) { return isItemFrame(type) || type == EntityTypes.PAINTING; }

    @Override
    public boolean isLiving(EntityType<?> type) {
        Boolean cached = LIVING.get(type);
        if (cached != null) {
            return cached;
        }
        Entity entity = type.create(observer.level(), EntitySpawnReason.LOAD);
        boolean living = entity instanceof LivingEntity;
        LIVING.put(type, living);
        return living;
    }

    @Override
    public Vec3 position(double x, double y, double z) { return new Vec3(x, y, z); }
    @Override
    public double x(Vec3 position) { return position.x; }
    @Override
    public double y(Vec3 position) { return position.y; }
    @Override
    public double z(Vec3 position) { return position.z; }
    @Override
    public RemoteProfile profile(ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view, UUID entityId) { return view.getProfile(entityId); }
    @Override
    public int stateVersion(ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view, UUID entityId) { return view.getStateVersion(entityId); }
    @Override
    public boolean hasMap(ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view, UUID entityId) {
        return MinecraftEntityMetadata.FRAMES.mapId(view.getMetadata(entityId)) != null;
    }

    @Override
    public void playerInfo(ServerPlayer observer, EntityRenderSpoofedEntity state, RemoteProfile profile) {
        packets.playerInfo(observer, state, profile);
    }

    @Override
    public void spawn(ServerPlayer observer, EntityRenderSpoofedEntity state, EntityRenderVisualProjector.Spawn<Vec3, EntityType<?>> spawn) {
        Vec3 position = spawn.position();
        MinecraftEntityPackets.send(observer, new ClientboundAddEntityPacket(state.fakeId, state.fakeUuid,
            position.x, position.y, position.z, spawn.pitch(), spawn.yaw(), spawn.type(), spawn.data(), spawn.velocity(), spawn.yaw()));
    }

    @Override
    public void spawnLabel(ServerPlayer observer, EntityRenderSpoofedEntity state, EntityRenderVisualProjector.Label<Vec3> label) {
        if (!state.playerEntry) {
            return;
        }
        Vec3 position = labelPosition(label);
        MinecraftEntityPackets.send(observer, new ClientboundAddEntityPacket(state.labelFakeId, state.labelFakeUuid,
            position.x, position.y, position.z, 0, 0, EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0));
        MinecraftEntityPackets.send(observer, new ClientboundSetEntityDataPacket(state.labelFakeId, labelMetadata(state.playerLabelText)));
        state.rememberLabelPosition(position.x, position.y, position.z);
    }

    @Override
    public void updateLabel(ServerPlayer observer, EntityRenderSpoofedEntity state, EntityRenderVisualProjector.Label<Vec3> label) {
        if (!state.playerEntry) {
            return;
        }
        Vec3 position = labelPosition(label);
        EntityRenderSpoofedEntity.Move move = state.updateLabelPosition(position.x, position.y, position.z);
        if (move.moved) {
            MinecraftEntityPackets.send(observer, move.relative
                ? new ClientboundMoveEntityPacket.Pos(state.labelFakeId, MinecraftEntityPackets.delta(move.deltaX, move.deltaY, move.deltaZ), false)
                : MinecraftEntityPackets.teleport(state.labelFakeId, position, 0, 0, false));
        }
        String text = ProjectedPlayerNames.playerLabelText(label.profile() == null ? null : label.profile().name());
        if (state.updatePlayerLabelText(text)) {
            MinecraftEntityPackets.send(observer, new ClientboundSetEntityDataPacket(state.labelFakeId,
                List.of(new SynchedEntityData.DataValue<>(23, EntityDataSerializers.COMPONENT, labelText(text)))));
        }
    }

    @Override
    public void entityState(ServerPlayer observer, EntityRenderSpoofedEntity state,
                            EntityRenderVisualProjector.State<ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment>> update) {
        List<SynchedEntityData.DataValue<?>> metadata = update.view().getMetadata(update.visual().id());
        if (metadata != null && !metadata.isEmpty()) {
            Integer sourceMapId = MinecraftEntityMetadata.FRAMES.mapId(metadata);
            ProjectedEntityMaps.Projection map = maps.project(observer, update.visual(), state,
                new ProjectedEntityMaps.Options(sourceMapId, update.metadataTransform(), update.initial()));
            metadata = MinecraftEntityMetadata.FRAMES.transformMetadata(metadata, update.metadataTransform(), map.mapId(), map.stripMapId());
            if (state.upsideDown) {
                metadata = update.visual().isPlayer() ? MinecraftEntityMetadata.ENTITIES.upsideDownPlayer(metadata)
                    : MinecraftEntityMetadata.ENTITIES.upsideDownEntity(metadata, false);
            }
            byte[] payload = blobs.writeMetadata(metadata);
            if (update.initial() || !Arrays.equals(payload, state.lastMetadataPayload)) {
                state.lastMetadataPayload = payload;
                MinecraftEntityPackets.send(observer, new ClientboundSetEntityDataPacket(state.fakeId, metadata));
            }
        }
        List<MinecraftPacketBlobs.Equipment> equipment = update.view().getEquipment(update.visual().id());
        if (equipment != null && !equipment.isEmpty()) {
            byte[] payload = blobs.writeEquipment(equipment);
            if (update.initial() || !Arrays.equals(payload, state.lastEquipmentPayload)) {
                state.lastEquipmentPayload = payload;
                List<Pair<EquipmentSlot, ItemStack>> items = new ArrayList<>(equipment.size());
                for (MinecraftPacketBlobs.Equipment item : equipment) {
                    items.add(Pair.of(item.slot(), item.item()));
                }
                MinecraftEntityPackets.send(observer, new ClientboundSetEquipmentPacket(state.fakeId, items));
            }
        }
    }

    @Override
    public void velocity(ServerPlayer observer, int entityId, Vec3 velocity) {
        MinecraftEntityPackets.send(observer, new ClientboundSetEntityMotionPacket(entityId, velocity));
    }

    @Override
    public void send(ServerPlayer observer, ProjectedMapData map, int virtualMapId) {
        MinecraftEntityPackets.send(observer, mapPacket(map, virtualMapId));
    }

    @Override
    public void invalid(UUID sourceId, String reason, RuntimeException error) {
        if (error == null) {
            LOGGER.warn("Wormholes rejected projected map data for {}: {}", sourceId, reason);
        } else {
            LOGGER.warn("Wormholes rejected projected map data for {}: {}", sourceId, reason, error);
        }
    }

    public static ClientboundMapItemDataPacket mapPacket(ProjectedMapData map, int virtualMapId) {
        return new ClientboundMapItemDataPacket(new MapId(virtualMapId), map.scale(), map.locked(), List.of(),
            new MapItemSavedData.MapPatch(0, 0, ProjectedMapData.WIDTH, ProjectedMapData.HEIGHT, map.pixels()));
    }

    public static List<SynchedEntityData.DataValue<?>> labelMetadata(String label) {
        return List.of(new SynchedEntityData.DataValue<>(10, EntityDataSerializers.INT, 3),
            new SynchedEntityData.DataValue<>(15, EntityDataSerializers.BYTE, (byte) 3),
            new SynchedEntityData.DataValue<>(16, EntityDataSerializers.INT, 0x00F000F0),
            new SynchedEntityData.DataValue<>(23, EntityDataSerializers.COMPONENT, labelText(label)),
            new SynchedEntityData.DataValue<>(25, EntityDataSerializers.INT, 0));
    }

    private static Component labelText(String label) {
        return Component.literal(ProjectedPlayerNames.playerLabelText(label)).withColor(0xFFFFFF);
    }

    private static Vec3 labelPosition(EntityRenderVisualProjector.Label<Vec3> label) {
        return new Vec3(label.position().x, ProjectedPlayerNames.labelY(label.position().y, label.height()), label.position().z);
    }
}
