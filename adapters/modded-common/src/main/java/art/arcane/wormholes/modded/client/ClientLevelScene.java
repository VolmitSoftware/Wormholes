package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftAcoustics;
import art.arcane.wormholes.modded.MinecraftAnimationParticles;
import art.arcane.wormholes.modded.MinecraftPacketBlobs;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.EntityVisualProjection;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.ProjectedPlayerNames;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class ClientLevelScene implements ClientSceneWorld {
    private static final int HEAD_LERP_STEPS = 3;

    private final ClientLevel level;
    private final Supplier<ClientPacketListener> listener;
    private final MinecraftPacketBlobs blobs;
    private final RandomSource random;
    private final Int2ObjectOpenHashMap<ClientItemMotion> itemMotion = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<UUID> playerProfiles = new Int2ObjectOpenHashMap<>();
    private long failures;

    public ClientLevelScene(ClientLevel level, Supplier<ClientPacketListener> listener) {
        this.level = Objects.requireNonNull(level, "level");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.blobs = new MinecraftPacketBlobs(level.registryAccess());
        this.random = RandomSource.create();
    }

    @Override
    public boolean spawn(int entityId, UUID projectionId, EntityVisual visual) {
        ClientPacketListener connection = listener.get();
        EntityType<?> type = type(visual.typeKey());
        if (connection == null || type == null) {
            return false;
        }
        try {
            if (type == EntityTypes.PLAYER) {
                playerInfo(projectionId, visual).handle(connection);
                playerProfiles.put(entityId, projectionId);
            }
            Vec3 velocity = new Vec3(visual.velocityX(), visual.velocityY(), visual.velocityZ());
            int data = hanging(type) ? Direction.getApproximateNearest(visual.lookX(), visual.lookY(), visual.lookZ()).get3DDataValue() : 0;
            new ClientboundAddEntityPacket(entityId, projectionId, visual.x(), visual.y(), visual.z(), visual.pitch(), visual.yaw(), type, data,
                velocity, headYaw(visual)).handle(connection);
            if (level.getEntity(entityId) != null) {
                return true;
            }
        } catch (RuntimeException failure) {
            failures++;
        }
        removePlayerInfo(entityId, connection);
        return false;
    }

    @Override
    public void move(int entityId, EntityVisual visual, EntityVisual previous) {
        Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return;
        }
        ClientItemMotion motion = itemMotion.get(entityId);
        if (motion != null) {
            motion.move(visual, previous);
        } else {
            move(entity, visual, previous);
        }
    }

    @Override
    public void tick(int entityId, boolean nativeMesh) {
        Entity entity = level.getEntity(entityId);
        if (!nativeMesh || !(entity instanceof ItemEntity item)) {
            itemMotion.remove(entityId);
            return;
        }
        ClientItemMotion motion = itemMotion.get(entityId);
        if (motion == null) {
            motion = new ClientItemMotion(item);
            itemMotion.put(entityId, motion);
        }
        motion.tick();
    }

    static void move(Entity entity, EntityVisual visual, EntityVisual previous) {
        if (visual.x() != previous.x() || visual.y() != previous.y() || visual.z() != previous.z()
            || visual.yaw() != previous.yaw() || visual.pitch() != previous.pitch()) {
            entity.moveOrInterpolateTo(new Vec3(visual.x(), visual.y(), visual.z()), visual.yaw(), visual.pitch());
        }
        if (visual.lookX() != previous.lookX() || visual.lookY() != previous.lookY() || visual.lookZ() != previous.lookZ()) {
            entity.lerpHeadTo(headYaw(visual), HEAD_LERP_STEPS);
        }
        if (visual.velocityX() != previous.velocityX() || visual.velocityY() != previous.velocityY()
            || visual.velocityZ() != previous.velocityZ()) {
            entity.lerpMotion(new Vec3(visual.velocityX(), visual.velocityY(), visual.velocityZ()));
        }
        if (visual.onGround() != previous.onGround()) {
            entity.setOnGround(visual.onGround());
        }
    }

    static float headYaw(EntityVisual visual) {
        return visual.lookX() * visual.lookX() + visual.lookZ() * visual.lookZ() < 1.0E-12D
            ? visual.yaw() : EntityVisualProjection.yaw(visual.lookX(), visual.lookZ());
    }

    @Override
    public void metadata(int entityId, byte[] metadata) {
        ClientPacketListener connection = listener.get();
        if (connection == null || metadata == null || metadata.length == 0 || level.getEntity(entityId) == null) {
            return;
        }
        try {
            List<SynchedEntityData.DataValue<?>> values = blobs.readMetadata(metadata);
            if (!values.isEmpty()) {
                new ClientboundSetEntityDataPacket(entityId, values).handle(connection);
            }
        } catch (RuntimeException failure) {
            failures++;
        }
    }

    @Override
    public void equipment(int entityId, byte[] equipment) {
        ClientPacketListener connection = listener.get();
        if (connection == null || equipment == null || equipment.length == 0 || level.getEntity(entityId) == null) {
            return;
        }
        try {
            List<MinecraftPacketBlobs.Equipment> items = blobs.readEquipment(equipment);
            List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>(items.size());
            for (MinecraftPacketBlobs.Equipment item : items) {
                slots.add(Pair.of(item.slot(), item.item()));
            }
            if (!slots.isEmpty()) {
                new ClientboundSetEquipmentPacket(entityId, slots).handle(connection);
            }
        } catch (RuntimeException failure) {
            failures++;
        }
    }

    @Override
    public void remove(int entityId, EntityVisual visual) {
        itemMotion.remove(entityId);
        if (level.getEntity(entityId) != null) {
            level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
        }
        removePlayerInfo(entityId, listener.get());
    }

    @Override
    public void particle(String key, double x, double y, double z, double spread, double speed, int count) {
        ParticleOptions options = particle(key);
        if (options == null) {
            return;
        }
        for (int i = 0; i < Math.max(1, count); i++) {
            level.addParticle(options, x + random.nextGaussian() * spread, y + random.nextGaussian() * spread, z + random.nextGaussian() * spread,
                random.nextGaussian() * speed, random.nextGaussian() * speed, random.nextGaussian() * speed);
        }
    }

    @Override
    public void burst(String key, double x, double y, double z, double spreadHorizontal, double spreadVertical, double speed, int count) {
        ParticleOptions options = particle(key);
        if (options != null) {
            spawn(options, x, y, z, count, spreadHorizontal, spreadVertical, spreadHorizontal, speed);
        }
    }

    @Override
    public void emission(PortalAnimation.ParticleEmission emission) {
        GeometryVector position = emission.position();
        GeometryVector spread = emission.spread();
        spawn(MinecraftAnimationParticles.options(emission.type()), position.x(), position.y(), position.z(), emission.count(), spread.x(),
            spread.y(), spread.z(), emission.speed());
    }

    @Override
    public void dust(double x, double y, double z, int rgb, float scale) {
        level.addParticle(new DustParticleOptions(rgb & 0xFFFFFF, scale <= 0.0F ? 1.0F : scale), x, y, z, 0.0D, 0.0D, 0.0D);
    }

    @Override
    public void sound(String key, double x, double y, double z, float volume, float pitch, AcousticsProfile.SoundClass soundClass) {
        Identifier id = Identifier.tryParse(key);
        if (id == null) {
            return;
        }
        level.playLocalSound(x, y, z, SoundEvent.createVariableRangeEvent(id), MinecraftAcoustics.source(soundClass), volume, pitch, false);
    }

    @Override
    public float rain() {
        return level.getRainLevel(1.0F);
    }

    @Override
    public float thunder() {
        float rain = level.getRainLevel(1.0F);
        return rain <= 0.0F ? 0.0F : Math.min(1.0F, level.getThunderLevel(1.0F) / rain);
    }

    @Override
    public void weather(float rain, float thunder) {
        level.setRainLevel(rain);
        level.setThunderLevel(thunder);
    }

    @Override
    public boolean hasClock() {
        return clockHolder().isPresent();
    }

    @Override
    public long clock() {
        Optional<Holder<WorldClock>> holder = clockHolder();
        return holder.map(clock -> level.clockManager().getInstance(clock).totalTicks()).orElse(0L);
    }

    @Override
    public void clock(long ticks) {
        Optional<Holder<WorldClock>> holder = clockHolder();
        if (holder.isEmpty()) {
            return;
        }
        float rate = level.clockManager().getInstance(holder.get()).rate();
        level.clockManager().handleUpdates(level.getGameTime(), Map.of(holder.get(), new ClockNetworkState(ticks, 0.0F, rate)));
        level.environmentAttributes().invalidateTickCache();
    }

    @Override
    public long gameTime() {
        return level.getGameTime();
    }

    public long failures() {
        return failures;
    }

    private Optional<Holder<WorldClock>> clockHolder() {
        return level.dimensionType().defaultClock();
    }

    private void removePlayerInfo(int entityId, ClientPacketListener connection) {
        UUID projectionId = playerProfiles.remove(entityId);
        if (connection != null && projectionId != null) {
            new ClientboundPlayerInfoRemovePacket(List.of(projectionId)).handle(connection);
        }
    }

    private ClientboundPlayerInfoUpdatePacket playerInfo(UUID projectionId, EntityVisual visual) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            buffer.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT), ClientboundPlayerInfoUpdatePacket.Action.class);
            buffer.writeVarInt(1);
            buffer.writeUUID(projectionId);
            buffer.writeUtf(ProjectedPlayerNames.playerLabelText(visual.playerName()), 16);
            boolean texture = visual.textureValue() != null && !visual.textureValue().isEmpty();
            buffer.writeVarInt(texture ? 1 : 0);
            if (texture) {
                buffer.writeUtf("textures");
                buffer.writeUtf(visual.textureValue());
                boolean signed = visual.textureSignature() != null && !visual.textureSignature().isEmpty();
                buffer.writeBoolean(signed);
                if (signed) {
                    buffer.writeUtf(visual.textureSignature());
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

    private void spawn(ParticleOptions options, double x, double y, double z, int count, double spreadX, double spreadY, double spreadZ,
                       double speed) {
        if (count == 0) {
            level.addParticle(options, x, y, z, speed * spreadX, speed * spreadY, speed * spreadZ);
            return;
        }
        for (int i = 0; i < count; i++) {
            double offsetX = random.nextGaussian() * spreadX;
            double offsetY = random.nextGaussian() * spreadY;
            double offsetZ = random.nextGaussian() * spreadZ;
            double velocityX = random.nextGaussian() * speed;
            double velocityY = random.nextGaussian() * speed;
            double velocityZ = random.nextGaussian() * speed;
            level.addParticle(options, x + offsetX, y + offsetY, z + offsetZ, velocityX, velocityY, velocityZ);
        }
    }

    private static ParticleOptions particle(String key) {
        Identifier id = key == null ? null : Identifier.tryParse(key);
        if (id == null) {
            return null;
        }
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null);
        return type instanceof ParticleOptions options ? options : null;
    }

    private static EntityType<?> type(String key) {
        Identifier id = key == null ? null : Identifier.tryParse(key);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    private static boolean hanging(EntityType<?> type) {
        return type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME || type == EntityTypes.PAINTING;
    }
}
