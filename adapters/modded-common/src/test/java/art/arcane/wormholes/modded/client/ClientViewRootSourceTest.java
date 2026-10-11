package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.FxMessage;
import art.arcane.wormholes.network.client.RootSource;
import art.arcane.wormholes.network.client.RootSourceExtension;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

public class ClientViewRootSourceTest extends MinecraftTestBase {
    @Test
    public void lateFirstRootMetadataCannotAdoptTheCurrentDestination() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.bindSourceWorld("minecraft:the_nether");
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        assertFalse(session.matchesSource(7, nether()));
        assertNull(session.rootSourceWorld(7));
        source(session, sink, 7, 1, "minecraft:overworld");
        assertEquals("minecraft:overworld", session.rootSourceWorld(7));
        assertFalse(session.matchesSource(7, nether()));
        session.bindSourceWorld("minecraft:the_nether");
        assertEquals("minecraft:overworld", session.rootSourceWorld(7));
    }

    @Test
    public void descendantsInheritTheRootAndWaitForMatchingGeometryMetadata() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        session.handle(new ViewStreamMessage.Portal(8, 1, ClientViewHarness.geometry().withParent(7)), sink);
        session.handle(new ViewStreamMessage.Portal(9, 1, ClientViewHarness.geometry().withParent(8)), sink);
        source(session, sink, 7, 1, "minecraft:the_nether");
        assertTrue(session.matchesSource(9, nether()));
        session.handle(new ViewStreamMessage.Portal(7, 2, ClientViewHarness.geometry()), sink);
        assertNull(session.rootSourceWorld(9));
        source(session, sink, 7, 1, "minecraft:the_nether");
        assertNull(session.rootSourceWorld(9));
        source(session, sink, 7, 2, "minecraft:the_nether");
        assertTrue(session.matchesSource(8, nether()));
        assertTrue(session.matchesSource(9, nether()));
    }

    @Test
    public void rootDropAndResetRemoveOwnershipAndDelayedMetadataIsIgnored() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        source(session, sink, 7, 1, "minecraft:the_nether");
        session.handle(new ViewStreamMessage.PortalDrop(7), sink);
        source(session, sink, 7, 1, "minecraft:the_nether");
        assertNull(session.rootSourceWorld(7));
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        assertNull(session.rootSourceWorld(7));
        source(session, sink, 7, 1, "minecraft:overworld");
        session.handle(new ViewStreamMessage.SessionReset(ViewStreamMessage.ResetReason.DIMENSION), sink);
        assertNull(session.rootSourceWorld(7));
    }

    @Test
    public void sourceCannotChangeWithinTheSameRootLifetimeOrBindToAChild() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        source(session, sink, 7, 1, "minecraft:overworld");
        assertThrows(ViewStreamProtocolException.class, () -> source(session, sink, 7, 1, "minecraft:the_nether"));
        assertEquals("minecraft:overworld", session.rootSourceWorld(7));
        session.handle(new ViewStreamMessage.Portal(8, 1, ClientViewHarness.geometry().withParent(7)), sink);
        assertThrows(ViewStreamProtocolException.class, () -> source(session, sink, 8, 1, "minecraft:the_nether"));
    }

    @Test
    public void ordinaryPaperFallbackKeepsRootsLocalAcrossAttachmentAndAllowsNewDestinationRoots() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL & ~ClientViewExtensions.ROOT_SOURCE);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.bindSourceWorld("minecraft:overworld");
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        session.bindSourceWorld("minecraft:the_nether");
        session.handle(new ViewStreamMessage.Portal(8, 1, ClientViewHarness.geometry().withParent(7)), sink);
        session.handle(new ViewStreamMessage.Portal(9, 1, ClientViewHarness.geometry()), sink);
        assertEquals("minecraft:overworld", session.rootSourceWorld(8));
        assertFalse(session.matchesSource(7, nether()));
        assertTrue(session.matchesSource(9, nether()));
        assertThrows(ViewStreamProtocolException.class, () -> source(session, sink, 9, 1, "minecraft:the_nether"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void projectedEntityAndEffectLookupRejectsPromotionBeforeTheAttachmentTick() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        source(session, sink, 7, 1, "minecraft:the_nether");
        ClientViewTick tick = new ClientViewTick(session, new ClientViewReceiver(session), session.config(), new ClientViewStats());
        ClientLevel owner = nether();
        Field levelField = ClientViewTick.class.getDeclaredField("sourceLevel");
        levelField.setAccessible(true);
        levelField.set(tick, owner);
        Field portalsField = ClientViewTick.class.getDeclaredField("portals");
        portalsField.setAccessible(true);
        IntFunction<ClientPortal> lookup = (IntFunction<ClientPortal>) portalsField.get(tick);
        Minecraft minecraft = mock(Minecraft.class);
        try (MockedStatic<Minecraft> game = mockStatic(Minecraft.class)) {
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            minecraft.level = owner;
            assertEquals(session.portal(7), lookup.apply(7));
            minecraft.level = mock(ClientLevel.class);
            assertNull(lookup.apply(7));
            levelField.set(tick, minecraft.level);
            when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
            assertNull(lookup.apply(7));
            assertEquals("minecraft:the_nether", session.rootSourceWorld(7));
        }
    }

    @Test
    public void positionalPortalEffectsWaitForTheirOwnerWhileWorldEffectsStillFire() {
        ClientSceneWorld world = mock(ClientSceneWorld.class);
        ClientFxRunner fx = new ClientFxRunner(world);
        FxMessage.FxEmitter dust = new FxMessage.FxEmitter(FxMessage.FxKind.RIM_DUST, "", 0, 64, 0, 0xFF0000, 1, 1, 1);
        FxMessage.FxEmitter sound = new FxMessage.FxEmitter(FxMessage.FxKind.SOUND, "minecraft:block.note_block.chime",
            0, 64, 0, 1, 1, 0, 0);
        fx.apply(new FxMessage.Fx(7, List.of(dust, sound)), key -> null, true);
        fx.tick(key -> null);
        verifyNoInteractions(world);
        assertEquals(1, fx.emitters(7));
        ClientPortal portal = mock(ClientPortal.class);
        fx.tick(key -> portal);
        assertEquals(1L, fx.fired());
        fx.apply(new FxMessage.Fx(FxMessage.WORLD_FX_KEY, List.of(sound)), key -> null, true);
        assertEquals(2L, fx.fired());
    }

    private static ClientViewSession session(long caps) {
        WormholesClientConfig config = new WormholesClientConfig();
        config.normalize();
        ClientViewSession session = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
        session.accept(new ViewStreamMessage.Accept(1, caps, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        return session;
    }

    private static ClientLevel nether() {
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.NETHER);
        return level;
    }

    private static void source(ClientViewSession session, ClientViewSession.Sink sink, int key, int revision, String world) throws Exception {
        session.handle(RootSourceExtension.INSTANCE.wrap(new RootSource(key, revision, world)), sink);
    }
}
