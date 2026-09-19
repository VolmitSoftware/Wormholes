package art.arcane.wormholes.render;

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

import art.arcane.wormholes.network.view.RemoteViewCache;

final class EntityRenderPlayerIdentity {
    private static final long PROFILE_REFRESH_NANOS = 500_000_000L;
    private static final AtomicInteger NEXT_NAME_TEAM_ID = new AtomicInteger();

    private final EntityRenderPacketChannel channel;
    private final String vanillaNameTeamName;
    private boolean vanillaNameTeamSent;
    private final Map<String, Integer> vanillaNameTeamMembers;

    private boolean labelsEnabled = true;

    EntityRenderPlayerIdentity(EntityRenderPacketChannel channel) {
        this.channel = channel;
        this.vanillaNameTeamName = "whpn" + Integer.toUnsignedString(NEXT_NAME_TEAM_ID.getAndIncrement(), 36);
        this.vanillaNameTeamSent = false;
        this.vanillaNameTeamMembers = new HashMap<String, Integer>(4);
    }

    void sendPlayerInfo(Player observer, Player player, EntityRenderSpoofedEntity state, boolean upsideDown) {
        String sourceName = player.getName();
        String label = ProjectedEntityRenderer.playerLabelText(sourceName);
        String name = ProjectedEntityRenderer.projectedProfileName(sourceName, state.fakeUuid, upsideDown);
        state.setPlayerIdentity(name, label);
        hideVanillaNametag(observer, name);
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

    boolean playerProfileChanged(Player player, EntityRenderSpoofedEntity state, long nowNanos) {
        if (nowNanos - state.playerProfileCheckedAtNanos < PROFILE_REFRESH_NANOS) {
            return false;
        }
        state.playerProfileCheckedAtNanos = nowNanos;
        return !Objects.equals(state.playerProfile, playerProfile(player));
    }

    void sendRemotePlayerInfo(Player observer, RemoteViewCache.RemoteProfile profile, EntityRenderSpoofedEntity state, boolean upsideDown) {
        state.playerProfile = profile;
        String sourceName = profile == null ? null : profile.name();
        String label = ProjectedEntityRenderer.playerLabelText(sourceName);
        String name = ProjectedEntityRenderer.projectedProfileName(sourceName, state.fakeUuid, upsideDown);
        state.setPlayerIdentity(name, label);
        hideVanillaNametag(observer, name);
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

    void spawnPlayerLabel(Player observer, EntityRenderSpoofedEntity state, Vector3d playerPosition, double playerHeight) {
        if (!state.playerEntry || !labelsEnabled) {
            return;
        }
        Vector3d labelPosition = ProjectedEntityRenderer.playerLabelPosition(playerPosition, playerHeight);
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.labelFakeId, Optional.of(state.labelFakeUuid),
            EntityTypes.TEXT_DISPLAY, labelPosition, 0.0F, 0.0F, 0.0F, 0, Optional.empty());
        channel.send(observer, spawn);
        channel.send(observer, new WrapperPlayServerEntityMetadata(state.labelFakeId, ProjectedEntityRenderer.playerLabelMetadata(state.playerLabelText)));
        state.rememberLabelPosition(labelPosition);
    }

    void updatePlayerLabelPosition(Player observer, EntityRenderSpoofedEntity state, Vector3d playerPosition, double playerHeight) {
        if (!state.playerEntry || !labelsEnabled) {
            return;
        }
        Vector3d labelPosition = ProjectedEntityRenderer.playerLabelPosition(playerPosition, playerHeight);
        EntityRenderSpoofedEntity.Move move = state.updateLabelPosition(labelPosition);
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

    void updatePlayerLabelText(Player observer, EntityRenderSpoofedEntity state, RemoteViewCache.RemoteProfile profile) {
        if (!state.playerEntry) {
            return;
        }
        String sourceName = profile == null ? null : profile.name();
        String label = ProjectedEntityRenderer.playerLabelText(sourceName);
        if (!state.updatePlayerLabelText(label)) {
            return;
        }
        channel.send(observer, new WrapperPlayServerEntityMetadata(state.labelFakeId, ProjectedEntityRenderer.playerLabelTextMetadata(label)));
    }

    void releaseVanillaNametag(Player observer, EntityRenderSpoofedEntity state) {
        String name = state.playerProfileName;
        if (name == null) {
            return;
        }
        Integer references = vanillaNameTeamMembers.get(name);
        if (references == null) {
            return;
        }
        if (references.intValue() > 1) {
            vanillaNameTeamMembers.put(name, references.intValue() - 1);
            return;
        }
        vanillaNameTeamMembers.remove(name);
        if (vanillaNameTeamSent) {
            channel.send(observer, new WrapperPlayServerTeams(vanillaNameTeamName,
                WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, name));
        }
    }

    void sendVanillaNameTeamRemoval(Player observer) {
        if (!vanillaNameTeamSent) {
            return;
        }
        channel.send(observer, new WrapperPlayServerTeams(vanillaNameTeamName,
            WrapperPlayServerTeams.TeamMode.REMOVE, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.of()));
    }

    void forgetVanillaNameTeam() {
        vanillaNameTeamMembers.clear();
        vanillaNameTeamSent = false;
    }

    boolean hasVanillaNameTeam() {
        return vanillaNameTeamSent;
    }

    private static RemoteViewCache.RemoteProfile playerProfile(Player player) {
        for (TextureProperty property : SpigotReflectionUtil.getUserProfile(player)) {
            if ("textures".equals(property.getName())) {
                return new RemoteViewCache.RemoteProfile(player.getName(), property.getValue(),
                    property.getSignature() == null ? "" : property.getSignature());
            }
        }
        return new RemoteViewCache.RemoteProfile(player.getName(), "", "");
    }

    private void hideVanillaNametag(Player observer, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        ensureVanillaNameTeam(observer);
        int references = vanillaNameTeamMembers.getOrDefault(name, 0);
        vanillaNameTeamMembers.put(name, references + 1);
        if (references == 0) {
            channel.send(observer, new WrapperPlayServerTeams(vanillaNameTeamName,
                WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, name));
        }
    }

    private void ensureVanillaNameTeam(Player observer) {
        if (vanillaNameTeamSent) {
            return;
        }
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(), Component.empty(), Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.NEVER,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE);
        channel.send(observer, new WrapperPlayServerTeams(vanillaNameTeamName,
            WrapperPlayServerTeams.TeamMode.CREATE, info, List.of()));
        vanillaNameTeamSent = true;
    }
}
