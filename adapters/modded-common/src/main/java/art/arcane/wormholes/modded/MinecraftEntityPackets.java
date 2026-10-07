package art.arcane.wormholes.modded;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.datafixers.util.Pair;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.TeamColor;

import art.arcane.optics.entity.EntityOutput;
import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.entity.MapSnapshot;
import art.arcane.optics.entity.PlayerNames;
import art.arcane.optics.entity.ProjectedMaps;
import art.arcane.optics.entity.SnapshotProjector;
import art.arcane.optics.entity.SpoofRegistry;
import art.arcane.optics.entity.SpoofedEntity;
import art.arcane.optics.view.EntityData;
import art.arcane.wormholes.modded.mixin.ProjectionEntityMapAccess;
import art.arcane.wormholes.render.ProjectedEntityIdentity;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class MinecraftEntityPackets implements EntityOutput<ServerPlayer, Vec3, EntityType<?>,
    EntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment>, Entity> {
    public static final int NO_ANIMATION = -1;
    public static final int ANIMATION_SWING_MAIN_HAND = 0;
    public static final int ANIMATION_WAKE_UP = 2;
    public static final int ANIMATION_SWING_OFF_HAND = 3;
    public static final int ANIMATION_CRITICAL_HIT = 4;
    public static final int ANIMATION_MAGIC_CRITICAL_HIT = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<EntityType<?>, Boolean> LIVING = new HashMap<>();

    private final WormholesModRuntime runtime;
    private final PlayerNames<ServerPlayer> names = new PlayerNames<>(this, ProjectedEntityIdentity.nextTeamName());
    private final ProjectedMaps<ServerPlayer> maps = new ProjectedMaps<>(this);
    private final Scoreboard teams = new Scoreboard();
    private MinecraftPacketBlobs blobs;

    public MinecraftEntityPackets(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public static ClientboundMapItemDataPacket mapPacket(MapSnapshot map, int virtualMapId) {
        return new ClientboundMapItemDataPacket(new MapId(virtualMapId), map.scale(), map.locked(), List.of(),
            new MapItemSavedData.MapPatch(0, 0, MapSnapshot.WIDTH, MapSnapshot.HEIGHT, map.pixels()));
    }

    public static List<SynchedEntityData.DataValue<?>> labelMetadata(String label) {
        return List.of(new SynchedEntityData.DataValue<>(10, EntityDataSerializers.INT, 3),
            new SynchedEntityData.DataValue<>(15, EntityDataSerializers.BYTE, (byte) 3),
            new SynchedEntityData.DataValue<>(16, EntityDataSerializers.INT, 0x00F000F0),
            new SynchedEntityData.DataValue<>(23, EntityDataSerializers.COMPONENT, labelText(label)),
            new SynchedEntityData.DataValue<>(25, EntityDataSerializers.INT, 0));
    }

    public void playerInfo(ServerPlayer observer, SpoofedEntity state, EntityProfile profile) {
        state.playerProfile = profile;
        String sourceName = profile == null ? null : profile.name();
        state.setPlayerIdentity(ProjectedEntityIdentity.NAMING.projectedProfileName(sourceName, state.fakeUuid, state.upsideDown),
            ProjectedEntityIdentity.NAMING.labelText(sourceName));
        names.retain(observer, state.playerProfileName);
        send(observer, playerInfo(observer.level().registryAccess(), state, profile));
    }

    public boolean hasNameTeam() {
        return names.hasTeam();
    }

    public void discard() {
        names.forget();
    }

    public void close(ServerPlayer observer) {
        names.removeTeam(observer);
        names.forget();
    }

    public static ClientboundPlayerInfoUpdatePacket playerInfo(RegistryAccess registries, SpoofedEntity state, EntityProfile profile) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            buffer.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT),
                ClientboundPlayerInfoUpdatePacket.Action.class);
            buffer.writeVarInt(1);
            buffer.writeUUID(state.fakeUuid);
            buffer.writeUtf(state.playerProfileName, 16);
            boolean texture = profile != null && profile.textureValue() != null && !profile.textureValue().isEmpty();
            buffer.writeVarInt(texture ? 1 : 0);
            if (texture) {
                buffer.writeUtf("textures");
                buffer.writeUtf(profile.textureValue());
                boolean signed = profile.textureSignature() != null && !profile.textureSignature().isEmpty();
                buffer.writeBoolean(signed);
                if (signed) {
                    buffer.writeUtf(profile.textureSignature());
                }
            }
            buffer.writeVarInt(0);
            buffer.writeBoolean(false);
            buffer.writeVarInt(0);
            buffer.writeBoolean(true);
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    public static int animationId(Packet<?> packet) {
        if (packet instanceof ClientboundSwingAnimationPacket swing) {
            return swing.hand() == InteractionHand.OFF_HAND ? ANIMATION_SWING_OFF_HAND : ANIMATION_SWING_MAIN_HAND;
        }
        if (packet instanceof ClientboundAnimatePacket animate) {
            return switch (animate.getAction()) {
                case ClientboundAnimatePacket.WAKE_UP -> ANIMATION_WAKE_UP;
                case ClientboundAnimatePacket.CRITICAL_HIT -> ANIMATION_CRITICAL_HIT;
                case ClientboundAnimatePacket.MAGIC_CRITICAL_HIT -> ANIMATION_MAGIC_CRITICAL_HIT;
                default -> NO_ANIMATION;
            };
        }
        return NO_ANIMATION;
    }

    public static boolean projectsAnimation(int animation) {
        return animation == ANIMATION_SWING_MAIN_HAND || animation == ANIMATION_SWING_OFF_HAND || animation == ANIMATION_WAKE_UP
            || animation == ANIMATION_CRITICAL_HIT || animation == ANIMATION_MAGIC_CRITICAL_HIT;
    }

    public static Packet<? super ClientGamePacketListener> animation(int entityId, int animation) {
        return switch (animation) {
            case ANIMATION_SWING_MAIN_HAND -> new ClientboundSwingAnimationPacket(entityId, InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
            case ANIMATION_SWING_OFF_HAND -> new ClientboundSwingAnimationPacket(entityId, InteractionHand.OFF_HAND, SwingAnimation.DEFAULT);
            case ANIMATION_WAKE_UP -> animatePacket(entityId, ClientboundAnimatePacket.WAKE_UP);
            case ANIMATION_CRITICAL_HIT -> animatePacket(entityId, ClientboundAnimatePacket.CRITICAL_HIT);
            case ANIMATION_MAGIC_CRITICAL_HIT -> animatePacket(entityId, ClientboundAnimatePacket.MAGIC_CRITICAL_HIT);
            default -> throw new IllegalArgumentException("Unsupported projected entity animation " + animation);
        };
    }

    public static void send(ServerPlayer observer, Packet<? super ClientGamePacketListener> packet) {
        if (!observer.level().getServer().isSameThread()) {
            throw new IllegalStateException("Projected entity packets require the server thread");
        }
        if (observer.connection.isAcceptingMessages()) {
            WormholesTelemetry.countPacket();
            observer.connection.send(packet);
        }
    }

    @Override
    public void motion(ServerPlayer observer, SpoofRegistry.Motion<Vec3> motion) {
        Packet<? super ClientGamePacketListener> packet = switch (motion.kind()) {
            case RELATIVE -> new ClientboundMoveEntityPacket.Pos(motion.entityId(), delta(motion.deltaX(), motion.deltaY(), motion.deltaZ()), motion.onGround());
            case RELATIVE_ROTATION -> new ClientboundMoveEntityPacket.PosRot(motion.entityId(), delta(motion.deltaX(), motion.deltaY(), motion.deltaZ()), Mth.packDegrees(motion.yaw()), Mth.packDegrees(motion.pitch()), motion.onGround());
            case TELEPORT -> teleport(motion.entityId(), motion.position(), motion.yaw(), motion.pitch(), motion.onGround());
            case ROTATION -> new ClientboundMoveEntityPacket.Rot(motion.entityId(), Mth.packDegrees(motion.yaw()), Mth.packDegrees(motion.pitch()), motion.onGround());
        };
        send(observer, packet);
    }

    public static ClientboundTeleportEntityPacket teleport(int entityId, Vec3 position, float yaw, float pitch, boolean onGround) {
        return new ClientboundTeleportEntityPacket(entityId, new PositionMoveRotation(position, Vec3.ZERO, yaw, pitch), Set.of(), onGround);
    }

    public static VecDelta delta(double x, double y, double z) {
        return new VecDelta.Linear(encodeDelta(x), encodeDelta(y), encodeDelta(z));
    }

    public static ClientboundRotateHeadPacket headPacket(int entityId, float yaw) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeByte(Mth.packDegrees(yaw));
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    public static ClientboundSetPassengersPacket passengersPacket(int entityId, int[] passengers) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeVarIntArray(passengers);
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    public static ClientboundSetEntityLinkPacket leashPacket(int entityId, int holderId) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(entityId);
            buffer.writeInt(holderId);
            return ClientboundSetEntityLinkPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    @Override
    public void headLook(ServerPlayer observer, int entityId, float yaw) {
        send(observer, headPacket(entityId, yaw));
    }

    @Override
    public void passengers(ServerPlayer observer, int entityId, int[] passengers) {
        send(observer, passengersPacket(entityId, passengers));
    }

    @Override
    public void leash(ServerPlayer observer, int entityId, int holderId) {
        send(observer, leashPacket(entityId, holderId));
    }

    @Override
    public void destroy(ServerPlayer observer, int[] entityIds) {
        send(observer, new ClientboundRemoveEntitiesPacket(entityIds));
    }

    @Override
    public void removePlayerInfo(ServerPlayer observer, List<UUID> playerIds) {
        send(observer, new ClientboundPlayerInfoRemovePacket(playerIds));
    }

    @Override
    public void releaseName(ServerPlayer observer, SpoofedEntity state) {
        names.release(observer, state.playerProfileName);
    }

    @Override
    public void team(ServerPlayer observer, TeamOp op, String team, String member) {
        switch (op) {
            case CREATE -> send(observer, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(hiddenNameTeam(team), true));
            case ADD -> send(observer, ClientboundSetPlayerTeamPacket.createPlayerPacket(teams.getPlayerTeam(team), member,
                ClientboundSetPlayerTeamPacket.Action.ADD));
            case REMOVE -> send(observer, ClientboundSetPlayerTeamPacket.createPlayerPacket(teams.getPlayerTeam(team), member,
                ClientboundSetPlayerTeamPacket.Action.REMOVE));
            case REMOVE_TEAM -> removeTeam(observer, teams.getPlayerTeam(team));
        }
    }

    @Override
    public int allocateEntityId() {
        return ProjectedEntityIdentity.nextEntityId();
    }

    @Override
    public EntityType<?> type(String key) {
        Identifier id = key == null ? null : Identifier.tryParse(key);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    @Override
    public boolean isItemFrame(EntityType<?> type) {
        return type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME;
    }

    @Override
    public boolean isHanging(EntityType<?> type) {
        return isItemFrame(type) || type == EntityTypes.PAINTING;
    }

    @Override
    public boolean isLiving(EntityType<?> type) {
        Boolean cached = LIVING.get(type);
        if (cached != null) {
            return cached;
        }
        Entity entity = type.create(runtime.server().overworld(), EntitySpawnReason.LOAD);
        boolean living = entity instanceof LivingEntity;
        LIVING.put(type, living);
        return living;
    }

    @Override
    public Vec3 position(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    @Override
    public double x(Vec3 position) {
        return position.x;
    }

    @Override
    public double y(Vec3 position) {
        return position.y;
    }

    @Override
    public double z(Vec3 position) {
        return position.z;
    }

    @Override
    public void spawn(ServerPlayer observer, SpoofedEntity state, SnapshotProjector.Spawn<Vec3, EntityType<?>> spawn) {
        Vec3 position = spawn.position();
        send(observer, new ClientboundAddEntityPacket(state.fakeId, state.fakeUuid,
            position.x, position.y, position.z, spawn.pitch(), spawn.yaw(), spawn.type(), spawn.data(), spawn.velocity(), spawn.yaw()));
    }

    @Override
    public void entityState(ServerPlayer observer, SpoofedEntity state,
                            SnapshotProjector.State<EntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment>> update) {
        List<SynchedEntityData.DataValue<?>> metadata = update.view().getMetadata(update.visual().id());
        if (metadata != null && !metadata.isEmpty()) {
            Integer sourceMapId = MinecraftEntityMetadata.FRAMES.mapId(metadata);
            ProjectedMaps.Projection map = maps.project(observer, update.visual(), state,
                new ProjectedMaps.Options(sourceMapId, update.metadataTransform(), update.initial()));
            metadata = MinecraftEntityMetadata.FRAMES.transformMetadata(metadata, update.metadataTransform(), map.mapId(), map.stripMapId());
            if (state.upsideDown) {
                metadata = update.visual().isPlayer() ? MinecraftEntityMetadata.ENTITIES.upsideDownPlayer(metadata)
                    : MinecraftEntityMetadata.ENTITIES.upsideDownEntity(metadata, false);
            }
            byte[] payload = blobs(observer).writeMetadata(metadata);
            if (update.initial() || !Arrays.equals(payload, state.lastMetadataPayload)) {
                state.lastMetadataPayload = payload;
                send(observer, new ClientboundSetEntityDataPacket(state.fakeId, metadata));
            }
        }
        List<MinecraftPacketBlobs.Equipment> equipment = update.view().getEquipment(update.visual().id());
        if (equipment != null && !equipment.isEmpty()) {
            byte[] payload = blobs(observer).writeEquipment(equipment);
            if (update.initial() || !Arrays.equals(payload, state.lastEquipmentPayload)) {
                state.lastEquipmentPayload = payload;
                List<Pair<EquipmentSlot, ItemStack>> items = new ArrayList<>(equipment.size());
                for (MinecraftPacketBlobs.Equipment item : equipment) {
                    items.add(Pair.of(item.slot(), item.item()));
                }
                send(observer, new ClientboundSetEquipmentPacket(state.fakeId, items));
            }
        }
    }

    @Override
    public void velocity(ServerPlayer observer, int entityId, Vec3 velocity) {
        send(observer, new ClientboundSetEntityMotionPacket(entityId, velocity));
    }

    @Override
    public void label(ServerPlayer observer, SpoofedEntity state, SnapshotProjector.Label<Vec3> label, boolean initial) {
        if (!state.playerEntry) {
            return;
        }
        Vec3 position = labelPosition(label);
        if (initial) {
            send(observer, new ClientboundAddEntityPacket(state.labelFakeId, state.labelFakeUuid,
                position.x, position.y, position.z, 0, 0, EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0));
            send(observer, new ClientboundSetEntityDataPacket(state.labelFakeId, labelMetadata(state.playerLabelText)));
            state.rememberLabelPosition(position.x, position.y, position.z);
            return;
        }
        SpoofedEntity.Move move = state.updateLabelPosition(position.x, position.y, position.z);
        if (move.moved) {
            send(observer, move.relative
                ? new ClientboundMoveEntityPacket.Pos(state.labelFakeId, delta(move.deltaX, move.deltaY, move.deltaZ), false)
                : teleport(state.labelFakeId, position, 0, 0, false));
        }
        String text = ProjectedEntityIdentity.NAMING.labelText(label.profile() == null ? null : label.profile().name());
        if (state.updatePlayerLabelText(text)) {
            send(observer, new ClientboundSetEntityDataPacket(state.labelFakeId,
                List.of(new SynchedEntityData.DataValue<>(23, EntityDataSerializers.COMPONENT, labelText(text)))));
        }
    }

    @Override
    public void map(ServerPlayer observer, MapSnapshot map, int virtualMapId) {
        send(observer, mapPacket(map, virtualMapId));
    }

    @Override
    public void hideLocal(ServerPlayer observer, Entity entity) {
        MinecraftEntityTracker tracker = tracker(entity);
        if (tracker != null) {
            tracker.wormholesHide(observer);
        }
    }

    @Override
    public void showLocal(ServerPlayer observer, Entity entity) {
        MinecraftEntityTracker tracker = tracker(entity);
        if (tracker != null) {
            tracker.wormholesShow(observer);
        }
    }

    @Override
    public boolean online(ServerPlayer observer) {
        return !observer.hasDisconnected();
    }

    @Override
    public UUID id(ServerPlayer observer) {
        return observer.getUUID();
    }

    @Override
    public void warning(ServerPlayer observer, String context, RuntimeException error) {
        LOGGER.warn("Wormholes {} for {}", context, observer == null ? "unknown" : observer.getUUID(), error);
    }

    private void removeTeam(ServerPlayer observer, PlayerTeam team) {
        send(observer, ClientboundSetPlayerTeamPacket.createRemovePacket(team));
        teams.removePlayerTeam(team);
    }

    private PlayerTeam hiddenNameTeam(String name) {
        PlayerTeam team = teams.addPlayerTeam(name);
        team.setNameTagVisibility(Team.Visibility.NEVER);
        team.setCollisionRule(Team.CollisionRule.NEVER);
        team.setColor(Optional.of(TeamColor.WHITE));
        return team;
    }

    private MinecraftPacketBlobs blobs(ServerPlayer observer) {
        if (blobs == null) {
            blobs = new MinecraftPacketBlobs(observer.level().registryAccess());
        }
        return blobs;
    }

    private MinecraftEntityTracker tracker(Entity entity) {
        runtime.requireServerThread();
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        Object tracker = ((ProjectionEntityMapAccess) level.getChunkSource().chunkMap).wormholesEntityMap().get(entity.getId());
        return tracker instanceof MinecraftEntityTracker projection ? projection : null;
    }

    private static Component labelText(String label) {
        return Component.literal(ProjectedEntityIdentity.NAMING.labelText(label)).withColor(0xFFFFFF);
    }

    private static Vec3 labelPosition(SnapshotProjector.Label<Vec3> label) {
        return new Vec3(label.position().x, PlayerNames.labelY(label.position().y, label.height()), label.position().z);
    }

    private static ClientboundAnimatePacket animatePacket(int entityId, int action) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeByte(action);
            return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static short encodeDelta(double value) {
        return (short) (value * 4096.0D);
    }
}
