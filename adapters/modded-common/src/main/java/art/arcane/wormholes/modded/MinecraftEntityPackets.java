package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.view.RemoteViewCache.RemoteProfile;
import art.arcane.wormholes.render.EntityRenderSpoofRegistry;
import art.arcane.wormholes.render.EntityRenderSpoofedEntity;
import art.arcane.wormholes.render.ProjectedPlayerNames;
import art.arcane.wormholes.service.WormholesTelemetry;
import io.netty.buffer.Unpooled;
import net.minecraft.world.scores.TeamColor;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MinecraftEntityPackets implements EntityRenderSpoofRegistry.Host<ServerPlayer, Vec3>, ProjectedPlayerNames.Host<ServerPlayer> {
    public static final int NO_ANIMATION = -1;
    public static final int ANIMATION_SWING_MAIN_HAND = 0;
    public static final int ANIMATION_WAKE_UP = 2;
    public static final int ANIMATION_SWING_OFF_HAND = 3;
    public static final int ANIMATION_CRITICAL_HIT = 4;
    public static final int ANIMATION_MAGIC_CRITICAL_HIT = 5;

    private final ProjectedPlayerNames<ServerPlayer> names = new ProjectedPlayerNames<>(this);
    private final Scoreboard teams = new Scoreboard();

    public void playerInfo(ServerPlayer observer, EntityRenderSpoofedEntity state, RemoteProfile profile) {
        state.playerProfile = profile;
        String sourceName = profile == null ? null : profile.name();
        state.setPlayerIdentity(ProjectedPlayerNames.projectedProfileName(sourceName, state.fakeUuid, state.upsideDown),
            ProjectedPlayerNames.playerLabelText(sourceName));
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

    public static ClientboundPlayerInfoUpdatePacket playerInfo(RegistryAccess registries, EntityRenderSpoofedEntity state, RemoteProfile profile) {
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
    public void motion(ServerPlayer observer, EntityRenderSpoofRegistry.Motion<Vec3> motion) {
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
    public void releaseName(ServerPlayer observer, EntityRenderSpoofedEntity state) {
        names.release(observer, state.playerProfileName);
    }

    @Override
    public void culled(ServerPlayer observer, UUID sourceId, EntityRenderSpoofedEntity state) {
    }

    @Override
    public void create(ServerPlayer observer, String teamName) {
        PlayerTeam team = teams.addPlayerTeam(teamName);
        team.setNameTagVisibility(Team.Visibility.NEVER);
        team.setCollisionRule(Team.CollisionRule.NEVER);
        team.setColor(Optional.of(TeamColor.WHITE));
        send(observer, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
    }

    @Override
    public void add(ServerPlayer observer, String teamName, String name) {
        send(observer, ClientboundSetPlayerTeamPacket.createPlayerPacket(teams.getPlayerTeam(teamName), name, ClientboundSetPlayerTeamPacket.Action.ADD));
    }

    @Override
    public void remove(ServerPlayer observer, String teamName, String name) {
        send(observer, ClientboundSetPlayerTeamPacket.createPlayerPacket(teams.getPlayerTeam(teamName), name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
    }

    @Override
    public void removeTeam(ServerPlayer observer, String teamName) {
        PlayerTeam team = teams.getPlayerTeam(teamName);
        send(observer, ClientboundSetPlayerTeamPacket.createRemovePacket(team));
        teams.removePlayerTeam(team);
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
