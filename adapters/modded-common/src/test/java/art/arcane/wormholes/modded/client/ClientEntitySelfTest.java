package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ClientEntitySelfTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void orderedReceiverBindingSurvivesWorldAttachmentAndClearsOnSessionReset() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        UUID id = UUID.randomUUID();
        harness.receive(new ClientViewMessage.EntitySelf(id), 0);
        harness.receive(new ClientViewMessage.EntityFrame(1, 1, List.of(player(id)), List.of(id), true), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(id, harness.session.selfEntityId());
        assertTrue(harness.tick.entities().presentPlayer(1, id));
        assertEquals(0, harness.tick.protocolFailures());
        harness.tick.revertEverything();
        harness.tick.detach();
        harness.tick.attach(new Object(), harness.surface, harness.scene);
        assertEquals(id, harness.session.selfEntityId());
        harness.receive(new ClientViewMessage.SessionReset(ClientViewMessage.ResetReason.DIMENSION), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertNull(harness.session.selfEntityId());
        assertEquals(0, harness.tick.entities().tracked());
        harness.receive(new ClientViewMessage.EntitySelf(UUID.randomUUID()), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        harness.session.abandon(harness.tick);
        assertNull(harness.session.selfEntityId());
    }

    @Test
    public void authorizedSelfUsesActualProfileAndIndependentPoseStatesWithoutChangingOtherPlayers() {
        Fixture fixture = new Fixture();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Local");
        when(fixture.player.getGameProfile()).thenReturn(profile);
        fixture.player.yBodyRot = 179;
        fixture.player.yHeadRot = 181;
        EntityRenderState first = new EntityRenderState();
        EntityRenderState second = new EntityRenderState();
        EntityRenderState other = new EntityRenderState();
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        when(renderer.extractEntity(fixture.otherClone, 0.5F)).thenReturn(other);
        when(renderer.extractEntity(fixture.player, 0.5F)).thenAnswer(call -> {
            assertNull(ClientMeshEntities.active());
            assertSame(profile, fixture.player.getGameProfile());
            assertEquals(179, fixture.player.yBodyRot, 0);
            assertEquals(181, fixture.player.yHeadRot, 0);
            return first;
        }).thenReturn(second);
        List<EntityRenderState> states = new ArrayList<>();
        fixture.scene.inDestinationWorld(() -> {
            fixture.extract(renderer, states);
            assertSame(fixture.scene, ClientMeshEntities.active(fixture.level));
        });
        assertEquals(List.of(other, first), states);
        states.clear();
        fixture.extract(renderer, states);
        assertEquals(List.of(other, second), states);
        verifyNoInteractions(fixture.selfClone);
        assertEquals(179, fixture.player.yBodyRot, 0);
        assertEquals(181, fixture.player.yHeadRot, 0);
        assertNull(ClientMeshEntities.active());
    }

    @Test
    public void withdrawnSelfPresenceUsesOnlyRemainingAuthorizedEntities() {
        Fixture fixture = new Fixture();
        fixture.projected.apply(new ClientViewMessage.EntityFrame(1, 2, List.of(), List.of(fixture.otherId), true));
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        EntityRenderState state = new EntityRenderState();
        when(renderer.extractEntity(fixture.otherClone, 0.5F)).thenReturn(state);
        List<EntityRenderState> states = new ArrayList<>();
        fixture.extract(renderer, states);
        assertEquals(List.of(state), states);
        verifyNoInteractions(fixture.player);
    }

    @Test
    public void mirrorMissingBindingAndDifferentDestinationKeepProjectedClonePath() {
        Fixture fixture = new Fixture();
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        when(fixture.geometry.mirror()).thenReturn(true);
        fixture.extract(renderer, new ArrayList<>());
        verify(renderer).extractEntity(fixture.selfClone, 0.5F);
        verifyNoInteractions(fixture.player);
        when(fixture.geometry.mirror()).thenReturn(false);
        when(fixture.level.dimension()).thenReturn(Level.NETHER);
        EntityRenderDispatcher differentWorld = mock(EntityRenderDispatcher.class);
        fixture.extract(differentWorld, new ArrayList<>());
        verify(differentWorld).extractEntity(fixture.selfClone, 0.5F);
        verifyNoInteractions(fixture.player);
        when(fixture.session.selfEntityId()).thenReturn(null);
        EntityRenderDispatcher noBinding = mock(EntityRenderDispatcher.class);
        fixture.extract(noBinding, new ArrayList<>());
        verify(noBinding).extractEntity(fixture.selfClone, 0.5F);
    }

    @Test
    public void selfExtractionFailureRestoresDestinationScope() {
        Fixture fixture = new Fixture();
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        when(renderer.extractEntity(fixture.player, 0.5F)).thenThrow(new IllegalStateException("extract"));
        fixture.scene.inDestinationWorld(() -> {
            assertThrows(IllegalStateException.class, () -> fixture.extract(renderer, new ArrayList<>()));
            assertSame(fixture.scene, ClientMeshEntities.active(fixture.level));
        });
        assertNull(ClientMeshEntities.active());
    }

    private static EntityVisual player(UUID id) {
        return EntityVisual.full(id, "minecraft:player", 1.5D, 64, 3, 1.8D, 0, 0, 1,
            0, 0, 0, 0, 0, true, "Projected", "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 1);
    }

    private static final class Fixture {
        private final UUID selfId = UUID.randomUUID();
        private final UUID otherId = UUID.randomUUID();
        private final ClientLevel level = mock(ClientLevel.class);
        private final LocalPlayer player = mock(LocalPlayer.class);
        private final Entity selfClone = mock(Entity.class);
        private final Entity otherClone = mock(Entity.class);
        private final ClientViewSession session = mock(ClientViewSession.class);
        private final ClientPortalGeometry geometry = mock(ClientPortalGeometry.class);
        private final ClientMeshEntities scene = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        private final ClientProjectedEntities projected;

        private Fixture() {
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(player.level()).thenReturn(level);
            when(session.selfEntityId()).thenReturn(selfId);
            ClientPortal portal = mock(ClientPortal.class);
            when(portal.geometry()).thenReturn(geometry);
            when(session.portal(1)).thenReturn(portal);
            when(session.environment(1)).thenReturn(PortalEnvironmentTest.environment(PortalEnvironmentTest.identity()));
            ClientSceneWorld world = mock(ClientSceneWorld.class);
            when(world.spawn(anyInt(), any(), any())).thenReturn(true);
            projected = new ClientProjectedEntities(world);
            projected.apply(new ClientViewMessage.EntityFrame(1, 1, List.of(player(selfId), player(otherId)), List.of(selfId, otherId), true));
            projected.tick(key -> portal, key -> true);
            when(level.getEntity(projected.entityId(1, selfId))).thenReturn(selfClone);
            when(level.getEntity(projected.entityId(1, otherId))).thenReturn(otherClone);
        }

        private void extract(EntityRenderDispatcher renderer, List<EntityRenderState> states) {
            scene.extractProjectedEntities(1, session, projected, player, renderer, 0.5F, states);
        }
    }
}
