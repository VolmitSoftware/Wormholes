package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import net.minecraft.client.Minecraft;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class WormholesClientSessionTest extends MinecraftTestBase {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void queuedCacheClaimsSurviveEqualIdentityAndRetireForEachProofComponentChange() throws Exception {
        for (int change = 0; change < 5; change++) {
            ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
            ClientViewSession session = harness.session;
            ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
            ApertureDescriptor geometry = ClientViewHarness.geometry();
            ProjectionEnvironment environment = PortalEnvironmentTest.environment(ProjectionEnvironment.Transform.IDENTITY);
            session.handle(new ClientViewMessage.Portal(1, 1, geometry), sink);
            session.handle(new ClientViewMessage.MeshBegin(1, 1, new PlateBox(-32, -32, -32, 64, 64, 64), 8), sink);
            session.handle(new ClientViewMessage.Environment(1, environment), sink);
            session.cacheClaims(1, List.of(new ClientViewMessage.MeshClaim(0, 0, 0, 77)));
            ProjectionEnvironment next = environment.withTransform(new ProjectionEnvironment.Transform(Face.E,
                Face.U, Face.S, new Vec3(0, 0, 0)));
            if (change == 1) {
                ProjectionEnvironment.World world = environment.world();
                next = new ProjectionEnvironment(environment.gameTime(), environment.sky(), environment.fog(),
                    environment.lighting(), environment.clouds(), environment.transform(), environment.dimension(),
                    new ProjectionEnvironment.World("minecraft:the_nether", world.clockTime(), world.biomeKey(),
                        world.seaLevel(), world.blockLight(), world.skyLight(), world.logicalHeight(), world.hasCeiling(),
                        world.ambientLight(), world.eyeMedium(), world.hasFixedTime()));
            } else if (change == 2) {
                next = environment.withTransform(new ProjectionEnvironment.Transform(Face.E, Face.U,
                    Face.S, new Vec3(16, 0, 0)));
            } else if (change == 3) {
                ApertureDescriptor changed = new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(),
                    geometry.facing(), geometry.frontSide(), geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(),
                    geometry.apertureHeight(), geometry.apertureMask(), geometry.nearPlanePadding(), geometry.aperturePadding(),
                    geometry.frustumCullingRatio(), geometry.depthBlocks(), geometry.recursionDepth(), geometry.blackoutPolicy(),
                    geometry.blackoutState(), geometry.maskAirPolicy(), geometry.lightingPolicy(), geometry.fidelityFlags(),
                    geometry.kind(), geometry.planeOffset(), geometry.parentPortalKey(), geometry.targetIdentity() + 1, geometry.nested());
                session.handle(new ClientViewMessage.Portal(1, 2, changed), sink);
            } else if (change == 4) {
                session.accept(new ClientViewMessage.Accept(1, ViewStreamCapability.ALL, 20,
                    ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 8L, 8));
            }
            session.handle(new ClientViewMessage.Environment(1, next), sink);
            List<ClientViewMessage> sent = new ArrayList<>();
            session.flushCached(sent::add);
            assertEquals("Identity component change " + change, change == 0 ? 1 : 0, sent.size());
            if (change == 0) {
                assertEquals(List.of(new ClientViewMessage.MeshClaim(0, 0, 0, 77)),
                    ((ClientViewMessage.MeshCached) sent.getFirst()).claims());
            }
        }
    }

    @Test
    public void pauseAndWindowFocusGateParticleCreationWithoutChangingClientSettings() throws Exception {
        WormholesClient client = WormholesClient.initialize(folder.newFolder().toPath(), bytes -> { });
        ClientViewHarness harness = new ClientViewHarness();
        client.session().accept(new ClientViewMessage.Accept(1, ViewStreamCapability.ALL, 20,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        client.tickState().attach(new Object(), harness.surface, harness.scene);
        Minecraft minecraft = mock(Minecraft.class);
        ClientViewMessage.Fx burst = new ClientViewMessage.Fx(ViewStreamLimits.WORLD_FX_KEY,
            List.of(ClientViewEmitters.burst("minecraft:reverse_portal", 1.5D, 65.0D, 10.5D, 12, 0.4D, 0.6D, 0.4D)));
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            try {
                when(minecraft.isPaused()).thenReturn(true);
                when(minecraft.isWindowActive()).thenReturn(true);
                client.receive(ClientViewCodec.encodeS2C(burst, 1, ViewStreamLimits.FLAG_LAST), null);
                client.tick(minecraft);
                assertEquals(0, harness.scene.particles.size());
                when(minecraft.isPaused()).thenReturn(false);
                when(minecraft.isWindowActive()).thenReturn(false);
                client.receive(ClientViewCodec.encodeS2C(burst, 2, ViewStreamLimits.FLAG_LAST), null);
                client.tick(minecraft);
                assertEquals(0, harness.scene.particles.size());
                client.receive(ClientViewCodec.encodeS2C(burst, 3, ViewStreamLimits.FLAG_LAST), null);
                when(minecraft.isWindowActive()).thenReturn(true);
                client.tick(minecraft);
                assertEquals(0, harness.scene.particles.size());
                client.receive(ClientViewCodec.encodeS2C(burst, 4, ViewStreamLimits.FLAG_LAST), null);
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
        previous.offer(new ClientViewMessage.Offer(ViewStreamLimits.WIRE_VERSION, 1, ViewStreamCapability.ALL,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 0L));
        previous.accept(new ClientViewMessage.Accept(1, ViewStreamCapability.ALL, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        assertEquals(ClientViewSession.State.CLIENT_VIEW, previous.state());

        Minecraft minecraft = mock(Minecraft.class);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            WormholesClient.reconfiguring();

            assertSame(client, WormholesClient.instance());
            assertNotSame(previous, client.session());
            assertEquals(ClientViewSession.State.INIT, client.session().state());
        }
    }
}
