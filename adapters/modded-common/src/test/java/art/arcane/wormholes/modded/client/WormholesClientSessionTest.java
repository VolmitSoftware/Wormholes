package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Face;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import net.minecraft.client.Minecraft;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import art.arcane.wormholes.network.client.FxMessage;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewExtensions;
import art.arcane.wormholes.network.client.FxExtension;

public class WormholesClientSessionTest extends MinecraftTestBase {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void missingCachedSectionRetractsItsClaimAndAcknowledgesTheFullReplacement() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        ClientViewSession session = harness.session;
        EnvironmentState environment = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
        BlockBox bounds = new BlockBox(-32, -32, -32, 64, 64, 64);
        session.handle(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"))), harness.tick);
        session.handle(new ViewStreamMessage.Portal(1, 1, ClientViewHarness.geometry()), harness.tick);
        session.handle(new ViewStreamMessage.MeshBegin(1, 1, bounds, 8), harness.tick);
        session.handle(new ViewStreamMessage.Environment(1, environment), harness.tick);
        session.handle(new ViewStreamMessage.MeshSection(1, 1, 0, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE), harness.tick);
        session.handle(new ViewStreamMessage.MeshBegin(1, 2, bounds, 8), harness.tick);
        session.handle(new ViewStreamMessage.Environment(1, environment), harness.tick);
        harness.sent.clear();
        session.flushCached(harness.sent::add);
        ViewStreamMessage.MeshCached available = (ViewStreamMessage.MeshCached) harness.sent.getFirst();
        ViewStreamMessage.MeshClaim claim = available.claims().getFirst();
        assertTrue(available.available());
        session.cacheClaims(1, List.of(claim, claim));
        assertTrue(session.meshes().drop(1, 2, 0, 0, 0));
        harness.sent.clear();
        session.handle(new ViewStreamMessage.MeshReuse(1, 2, 0, 0, 0, 2, claim.hash()), harness.tick);
        session.flushCached(harness.sent::add);
        assertEquals(List.of(new ViewStreamMessage.MeshCached(1, 2, available.sequence() + 1, false, List.of(claim))), harness.sent);
        harness.sent.clear();
        session.handle(new ViewStreamMessage.MeshSection(1, 2, 0, 0, 0, 3, 3, Brick.single(0, 3), SectionBiomes.NONE), harness.tick);
        assertEquals(List.of(new ViewStreamMessage.MeshAck(1, 2, 0, 0, 0, 3)), harness.sent);
        assertSame(ClientViewHarness.STONE, session.meshes().view(1).section(0L).state(0));
        harness.sent.clear();
        session.handle(new ViewStreamMessage.MeshReuse(1, 1, 0, 0, 0, 4, claim.hash()), harness.tick);
        session.handle(new ViewStreamMessage.MeshReuse(1, 2, 0, 0, 0, 2, claim.hash()), harness.tick);
        assertTrue(harness.sent.isEmpty());
        session.handle(new ViewStreamMessage.MeshReuse(1, 2, 0, 0, 0, 3, claim.hash()), harness.tick);
        assertEquals(List.of(new ViewStreamMessage.MeshAck(1, 2, 0, 0, 0, 3)), harness.sent);
        assertEquals(ClientViewSession.State.CLIENT_VIEW, session.state());
    }

    @Test
    public void queuedCacheClaimsSurviveEqualIdentityAndRetireForEachProofComponentChange() throws Exception {
        for (int change = 0; change < 5; change++) {
            ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
            ClientViewSession session = harness.session;
            ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
            ApertureDescriptor geometry = ClientViewHarness.geometry();
            EnvironmentState environment = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
            session.handle(new ViewStreamMessage.Portal(1, 1, geometry), sink);
            session.handle(new ViewStreamMessage.MeshBegin(1, 1, new BlockBox(-32, -32, -32, 64, 64, 64), 8), sink);
            session.handle(new ViewStreamMessage.Environment(1, environment), sink);
            session.cacheClaims(1, List.of(new ViewStreamMessage.MeshClaim(0, 0, 0, 77)));
            EnvironmentState next = environment.withTransform(OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), 0, 0, 0));
            if (change == 1) {
                EnvironmentState.World world = environment.world();
                next = new EnvironmentState(environment.gameTime(), environment.sky(), environment.fog(),
                    environment.lighting(), environment.clouds(), environment.transform(), environment.dimension(),
                    new EnvironmentState.World("minecraft:the_nether", world.clockTime(), world.biomeKey(),
                        world.seaLevel(), world.blockLight(), world.skyLight(), world.logicalHeight(), world.hasCeiling(),
                        world.ambientLight(), world.eyeMedium(), world.hasFixedTime()), 1.0F);
            } else if (change == 2) {
                next = environment.withTransform(OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), 16, 0, 0));
            } else if (change == 3) {
                ApertureDescriptor changed = new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(),
                    geometry.facing(), geometry.frontSide(), geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(),
                    geometry.apertureHeight(), geometry.apertureMask(), ShapeDescriptor.FULL, geometry.nearPlanePadding(), geometry.aperturePadding(),
                    geometry.frustumCullingRatio(), geometry.depthBlocks(), geometry.recursionDepth(), geometry.blackoutPolicy(),
                    geometry.blackoutState(), geometry.maskAirPolicy(), geometry.lightingPolicy(), geometry.fidelityFlags(),
                    geometry.kind(), geometry.planeOffset(), geometry.parentPortalKey(), geometry.targetIdentity() + 1, geometry.nested());
                session.handle(new ViewStreamMessage.Portal(1, 2, changed), sink);
            } else if (change == 4) {
                session.accept(new ViewStreamMessage.Accept(1, ViewStreamCapability.ALL, 20,
                    ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 8L, 8));
            }
            session.handle(new ViewStreamMessage.Environment(1, next), sink);
            List<ViewStreamMessage> sent = new ArrayList<>();
            session.flushCached(sent::add);
            assertEquals("Identity component change " + change, change == 0 ? 1 : 0, sent.size());
            if (change == 0) {
                assertEquals(List.of(new ViewStreamMessage.MeshClaim(0, 0, 0, 77)),
                    ((ViewStreamMessage.MeshCached) sent.getFirst()).claims());
            }
        }
    }

    @Test
    public void pauseAndWindowFocusGateParticleCreationWithoutChangingClientSettings() throws Exception {
        WormholesClient client = WormholesClient.initialize(folder.newFolder().toPath(), bytes -> { });
        ClientViewHarness harness = new ClientViewHarness();
        client.session().accept(new ViewStreamMessage.Accept(1, ViewStreamCapability.ALL, 20,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        client.tickState().attach(new Object(), harness.surface, harness.scene);
        Minecraft minecraft = mock(Minecraft.class);
        FxMessage.Fx burst = new FxMessage.Fx(FxMessage.WORLD_FX_KEY,
            List.of(ClientViewEmitters.burst("minecraft:reverse_portal", 1.5D, 65.0D, 10.5D, 12, 0.4D, 0.6D, 0.4D)));
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            when(minecraft.isSameThread()).thenReturn(true);
            try {
                when(minecraft.isPaused()).thenReturn(true);
                when(minecraft.isWindowActive()).thenReturn(true);
                client.receive(MinecraftClientViewExtensions.CODEC.encodeS2C(FxExtension.INSTANCE.wrap(burst), 1, ViewStreamLimits.FLAG_LAST), null);
                client.tick(minecraft);
                assertEquals(0, harness.scene.particles.size());
                when(minecraft.isPaused()).thenReturn(false);
                when(minecraft.isWindowActive()).thenReturn(false);
                client.receive(MinecraftClientViewExtensions.CODEC.encodeS2C(FxExtension.INSTANCE.wrap(burst), 2, ViewStreamLimits.FLAG_LAST), null);
                client.tick(minecraft);
                assertEquals(0, harness.scene.particles.size());
                client.receive(MinecraftClientViewExtensions.CODEC.encodeS2C(FxExtension.INSTANCE.wrap(burst), 3, ViewStreamLimits.FLAG_LAST), null);
                when(minecraft.isWindowActive()).thenReturn(true);
                client.tick(minecraft);
                assertEquals(0, harness.scene.particles.size());
                client.receive(MinecraftClientViewExtensions.CODEC.encodeS2C(FxExtension.INSTANCE.wrap(burst), 4, ViewStreamLimits.FLAG_LAST), null);
                client.tick(minecraft);
                assertEquals(List.of("burst minecraft:reverse_portal x12"), harness.scene.particles);
            } finally {
                client.disconnected();
                ProjectionOverlay.deactivate(harness.tick.overlay());
            }
        }
    }

    @Test
    public void reconfiguringTheConnectionStartsAFreshSession() throws IOException {
        WormholesClient client = WormholesClient.initialize(folder.newFolder().toPath(), bytes -> { });
        ClientViewSession previous = client.session();
        previous.offer(new ViewStreamMessage.Offer(ViewStreamLimits.WIRE_VERSION, 1, ViewStreamCapability.ALL,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 0L));
        previous.accept(new ViewStreamMessage.Accept(1, ViewStreamCapability.ALL, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        assertEquals(ClientViewSession.State.CLIENT_VIEW, previous.state());

        Minecraft minecraft = mock(Minecraft.class);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            when(minecraft.isSameThread()).thenReturn(true);
            WormholesClient.reconfiguring();

            assertSame(client, WormholesClient.instance());
            assertNotSame(previous, client.session());
            assertEquals(ClientViewSession.State.INIT, client.session().state());
        }
    }

    @Test
    public void disconnectReportedOffTheClientThreadRunsOnTheClientThread() throws IOException {
        WormholesClient client = WormholesClient.initialize(folder.newFolder().toPath(), bytes -> { });
        ClientViewSession previous = client.session();
        Minecraft minecraft = mock(Minecraft.class);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            when(minecraft.isSameThread()).thenReturn(false);

            client.disconnected();

            assertSame(previous, client.session());
            ArgumentCaptor<Runnable> scheduled = ArgumentCaptor.forClass(Runnable.class);
            verify(minecraft).execute(scheduled.capture());
            when(minecraft.isSameThread()).thenReturn(true);
            scheduled.getValue().run();
            assertNotSame(previous, client.session());
        }
    }
}
