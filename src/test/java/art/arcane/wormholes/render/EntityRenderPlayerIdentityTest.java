package art.arcane.wormholes.render;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;

import art.arcane.wormholes.network.view.RemoteViewCache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class EntityRenderPlayerIdentityTest {
    @Test
    void refreshesProfilesAtTheIntervalAndDetectsLateOrChangedSkins() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Server server = mock(Server.class);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            try (MockedStatic<SpigotReflectionUtil> reflection = mockStatic(SpigotReflectionUtil.class)) {
                Player player = ProjectedEntityPacketRecorder.player(true);
                EntityRenderSpoofedEntity state = EntityRenderSpoofedEntity.create(true, false, true);
                state.playerProfile = new RemoteViewCache.RemoteProfile("Observer", "", "");
                state.playerProfileCheckedAtNanos = 1_000_000_000L;
                EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(new EntityRenderPacketChannel());
                reflection.when(() -> SpigotReflectionUtil.getUserProfile(player)).thenReturn(List.of());

                assertFalse(identity.playerProfileChanged(player, state, 1_499_999_999L));
                reflection.verifyNoInteractions();
                assertFalse(identity.playerProfileChanged(player, state, 1_500_000_000L));
                assertEquals(1_500_000_000L, state.playerProfileCheckedAtNanos);
                reflection.verify(() -> SpigotReflectionUtil.getUserProfile(player), times(1));

                reflection.when(() -> SpigotReflectionUtil.getUserProfile(player))
                    .thenReturn(List.of(new TextureProperty("textures", "skin", "signature")));
                assertFalse(identity.playerProfileChanged(player, state, 1_999_999_999L));
                reflection.verify(() -> SpigotReflectionUtil.getUserProfile(player), times(1));
                assertTrue(identity.playerProfileChanged(player, state, 2_000_000_000L));

                state.playerProfile = new RemoteViewCache.RemoteProfile("Observer", "skin", "signature");
                assertFalse(identity.playerProfileChanged(player, state, 2_500_000_000L));
                reflection.when(() -> SpigotReflectionUtil.getUserProfile(player))
                    .thenReturn(List.of(new TextureProperty("textures", "skin", "new-signature")));
                assertTrue(identity.playerProfileChanged(player, state, 3_000_000_000L));

                state.playerProfile = new RemoteViewCache.RemoteProfile("Observer", "skin", "new-signature");
                when(player.getName()).thenReturn("Renamed");
                assertTrue(identity.playerProfileChanged(player, state, 3_500_000_000L));
                reflection.when(() -> SpigotReflectionUtil.getUserProfile(player))
                    .thenReturn(List.of(new TextureProperty("textures", "new-skin", "new-signature")));
                state.playerProfile = new RemoteViewCache.RemoteProfile("Renamed", "skin", "new-signature");
                assertTrue(identity.playerProfileChanged(player, state, 4_000_000_000L));
            }
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    void sendsAndRemembersSnapshotSkinWithoutChangingSignature() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            RemoteViewCache.RemoteProfile profile = new RemoteViewCache.RemoteProfile("Player", "snapshot-skin", "snapshot-signature");
            EntityRenderSpoofedEntity state = EntityRenderSpoofedEntity.create(true, false, true);
            EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(new EntityRenderPacketChannel());

            identity.sendRemotePlayerInfo(observer, profile, state, false);

            WrapperPlayServerPlayerInfoUpdate packet = recorder.sentOfType(WrapperPlayServerPlayerInfoUpdate.class).getFirst();
            UserProfile sentProfile = packet.getEntries().getFirst().getGameProfile();
            assertSame(profile, state.playerProfile);
            assertEquals(state.fakeUuid, sentProfile.getUUID());
            assertEquals(1, sentProfile.getTextureProperties().size());
            TextureProperty texture = sentProfile.getTextureProperties().getFirst();
            assertEquals("textures", texture.getName());
            assertEquals("snapshot-skin", texture.getValue());
            assertEquals("snapshot-signature", texture.getSignature());
        } finally {
            recorder.uninstall();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendsCurrentSkinWhenLoginProfileIsEmptyOrStale(boolean staleLoginSkin) {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.installWithBatchUser();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Server server = mock(Server.class);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            try (MockedStatic<SpigotReflectionUtil> reflection = mockStatic(SpigotReflectionUtil.class)) {
                Player player = ProjectedEntityPacketRecorder.player(true);
                when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
                UserProfile loginProfile = recorder.getPlayerManager().getUser(player).getProfile();
                if (staleLoginSkin) {
                    loginProfile.getTextureProperties().add(new TextureProperty("textures", "old-skin", "old-signature"));
                }
                reflection.when(() -> SpigotReflectionUtil.getUserProfile(player))
                    .thenReturn(List.of(new TextureProperty("textures", "current-skin", "current-signature")));
                EntityRenderSpoofedEntity state = EntityRenderSpoofedEntity.create(true, false, true);
                EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(new EntityRenderPacketChannel());

                identity.sendPlayerInfo(player, player, state, false);

                List<WrapperPlayServerPlayerInfoUpdate> packets = recorder.sentOfType(WrapperPlayServerPlayerInfoUpdate.class);
                assertEquals(1, packets.size());
                WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = packets.getFirst().getEntries().getFirst();
                assertEquals(state.fakeUuid, info.getGameProfile().getUUID());
                assertFalse(info.isListed());
                List<TextureProperty> textures = info.getGameProfile().getTextureProperties();
                assertEquals(1, textures.size());
                assertEquals("textures", textures.getFirst().getName());
                assertEquals("current-skin", textures.getFirst().getValue());
                assertEquals("current-signature", textures.getFirst().getSignature());
                reflection.verify(() -> SpigotReflectionUtil.getUserProfile(player));
            }
        } finally {
            recorder.uninstall();
        }
    }
}
