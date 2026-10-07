package art.arcane.wormholes.render;

import art.arcane.optics.entity.EntityProfile;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import art.arcane.optics.entity.PlayerNames;
import art.arcane.optics.entity.SpoofedEntity;
import art.arcane.optics.entity.EntityOutput;

final class EntityRenderPlayerIdentity {
    private static final long PROFILE_REFRESH_NANOS = 500_000_000L;

    private final EntityRenderPacketChannel channel;
    private final PlayerNames<Player> names;

    private boolean labelsEnabled = true;

    EntityRenderPlayerIdentity(EntityRenderPacketChannel channel, EntityOutput<Player, ?, ?, ?, ?> output) {
        this.channel = channel;
        this.names = new PlayerNames<>(output, ProjectedEntityIdentity.nextTeamName());
    }

    void sendPlayerInfo(Player observer, Player player, SpoofedEntity state, boolean upsideDown) {
        String sourceName = player.getName();
        String label = ProjectedEntityIdentity.NAMING.labelText(sourceName);
        String name = ProjectedEntityIdentity.NAMING.projectedProfileName(sourceName, state.fakeUuid, upsideDown);
        state.setPlayerIdentity(name, label);
        names.retain(observer, name);
        UserProfile userProfile = new UserProfile(state.fakeUuid, name);
        state.playerProfile = playerProfile(player);
        state.playerProfileCheckedAtNanos = System.nanoTime();
        if (!state.playerProfile.textureValue().isEmpty()) {
            userProfile.getTextureProperties().add(new TextureProperty("textures", state.playerProfile.textureValue(),
                state.playerProfile.textureSignature().isEmpty() ? null : state.playerProfile.textureSignature()));
        }
        GameMode gameMode = SpigotConversionUtil.fromBukkitGameMode(player.getGameMode());
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            userProfile, false, player.getPing(), gameMode, null, null, 0, true);
        channel.send(observer, new WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT),
            info));
    }

    boolean playerProfileChanged(Player player, SpoofedEntity state, long nowNanos) {
        if (nowNanos - state.playerProfileCheckedAtNanos < PROFILE_REFRESH_NANOS) {
            return false;
        }
        state.playerProfileCheckedAtNanos = nowNanos;
        return !Objects.equals(state.playerProfile, playerProfile(player));
    }

    void sendRemotePlayerInfo(Player observer, EntityProfile profile, SpoofedEntity state, boolean upsideDown) {
        state.playerProfile = profile;
        String sourceName = profile == null ? null : profile.name();
        String label = ProjectedEntityIdentity.NAMING.labelText(sourceName);
        String name = ProjectedEntityIdentity.NAMING.projectedProfileName(sourceName, state.fakeUuid, upsideDown);
        state.setPlayerIdentity(name, label);
        names.retain(observer, name);
        UserProfile userProfile = new UserProfile(state.fakeUuid, name);
        if (profile != null && profile.textureValue() != null && !profile.textureValue().isEmpty()) {
            String signature = profile.textureSignature() == null || profile.textureSignature().isEmpty() ? null : profile.textureSignature();
            userProfile.getTextureProperties().add(new TextureProperty("textures", profile.textureValue(), signature));
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            userProfile, false, 0, GameMode.SURVIVAL, null, null, 0, true);
        channel.send(observer, new WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_HAT),
            info));
    }

    void setLabelsEnabled(boolean enabled) {
        labelsEnabled = enabled;
    }

    void spawnPlayerLabel(Player observer, SpoofedEntity state, Vector3d playerPosition, double playerHeight) {
        if (!state.playerEntry || !labelsEnabled) {
            return;
        }
        Vector3d labelPosition = ProjectedEntityRenderer.playerLabelPosition(playerPosition, playerHeight);
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.labelFakeId, Optional.of(state.labelFakeUuid),
            EntityTypes.TEXT_DISPLAY, labelPosition, 0.0F, 0.0F, 0.0F, 0, Optional.empty());
        channel.send(observer, spawn);
        channel.send(observer, new WrapperPlayServerEntityMetadata(state.labelFakeId, ProjectedEntityRenderer.playerLabelMetadata(state.playerLabelText)));
        state.rememberLabelPosition(labelPosition.getX(), labelPosition.getY(), labelPosition.getZ());
    }

    void updatePlayerLabelPosition(Player observer, SpoofedEntity state, Vector3d playerPosition, double playerHeight) {
        if (!state.playerEntry || !labelsEnabled) {
            return;
        }
        Vector3d labelPosition = ProjectedEntityRenderer.playerLabelPosition(playerPosition, playerHeight);
        SpoofedEntity.Move move = state.updateLabelPosition(labelPosition.getX(), labelPosition.getY(), labelPosition.getZ());
        if (!move.moved) {
            return;
        }
        if (move.relative) {
            channel.send(observer, new WrapperPlayServerEntityRelativeMove(
                state.labelFakeId, move.deltaX, move.deltaY, move.deltaZ, false));
            return;
        }
        channel.send(observer, new WrapperPlayServerEntityTeleport(state.labelFakeId, labelPosition, 0.0F, 0.0F, false));
    }

    void updatePlayerLabelText(Player observer, SpoofedEntity state, EntityProfile profile) {
        if (!state.playerEntry) {
            return;
        }
        String sourceName = profile == null ? null : profile.name();
        String label = ProjectedEntityIdentity.NAMING.labelText(sourceName);
        if (!state.updatePlayerLabelText(label)) {
            return;
        }
        channel.send(observer, new WrapperPlayServerEntityMetadata(state.labelFakeId, ProjectedEntityRenderer.playerLabelTextMetadata(label)));
    }

    void releaseVanillaNametag(Player observer, SpoofedEntity state) {
        names.release(observer, state.playerProfileName);
    }

    void sendVanillaNameTeamRemoval(Player observer) {
        names.removeTeam(observer);
    }

    void forgetVanillaNameTeam() {
        names.forget();
    }

    boolean hasVanillaNameTeam() {
        return names.hasTeam();
    }

    private static EntityProfile playerProfile(Player player) {
        for (TextureProperty property : SpigotReflectionUtil.getUserProfile(player)) {
            if ("textures".equals(property.getName())) {
                return new EntityProfile(player.getName(), property.getValue(),
                    property.getSignature() == null ? "" : property.getSignature());
            }
        }
        return new EntityProfile(player.getName(), "", "");
    }

}
