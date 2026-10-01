package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftLocalEntityView;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalProjector;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftProjectionService;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.ProjectorRecursivePortals;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftClientViewPortalAccessTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void clientViewAndVanillaObserversOfOneSideShareOnePlate() {
        Fixture fixture = fixture(PortalType.PORTAL);
        try (MinecraftPortalProjector projector = new MinecraftPortalProjector(fixture.runtime(),
            new MinecraftPortalProjector.Context(fixture.vanilla(), fixture.source(), ignored -> fixture.view(), fixture.access(), fixture.cache()))) {
            assertEquals(MinecraftPortalProjector.Result.READY, projector.update(1L, Long.MAX_VALUE));
        }
        assertEquals(1, fixture.scheduled().size());
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        assertFalse(portals.refused(fixture.peer(), fixture.source().getId()));
        assertEquals(1, fixture.scheduled().size());
        assertEquals(fixture.source().getId(), fixture.scheduled().get(0).key().portalId());
        assertTrue(fixture.scheduled().get(0).key().frontSide());
    }

    @Test
    public void rtpObserversShareOnlyTheirOwnRoute() {
        Fixture fixture = fixture(PortalType.RTP);
        when(fixture.rtp().plateIdentity(any(), any())).thenReturn(0L);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        assertEquals(0, fixture.scheduled().size());
        assertFalse(portals.refused(fixture.peer(), fixture.source().getId()));
        when(fixture.rtp().plateIdentity(any(), any())).thenReturn(91L);
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        assertEquals(1, fixture.scheduled().size());
        assertEquals(91L, fixture.scheduled().get(0).key().targetIdentity());
        ClientPortalGeometry geometry = portals.geometry(fixture.peer(), fixture.source().getId(), new SessionPalette());
        assertEquals(ClientPortalGeometry.KIND_RTP, geometry.kind());
        assertNotEquals(0L, geometry.targetIdentity());
        assertNotEquals(91L, geometry.targetIdentity());
        boolean gate = FidelitySettings.rtpPlates;
        FidelitySettings.rtpPlates = false;
        try {
            assertTrue(portals.refused(fixture.peer(), fixture.source().getId()));
        } finally {
            FidelitySettings.rtpPlates = gate;
        }
    }

    @Test
    public void remoteTunnelsAndDisabledPlatesStayOnTheVanillaPath() {
        Fixture fixture = fixture(PortalType.PORTAL);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        assertTrue(MinecraftClientViewPortalAccess.ownable(fixture.source()));
        when(fixture.source().getTunnelType()).thenReturn("UNIVERSAL");
        assertFalse(MinecraftClientViewPortalAccess.ownable(fixture.source()));
        assertTrue(portals.refused(fixture.peer(), fixture.source().getId()));
        assertNull(portals.geometry(fixture.peer(), fixture.source().getId(), new SessionPalette()));
        portals.frame(List.of(fixture.source()));
        List<UUID> interest = new ArrayList<>();
        portals.interested(fixture.peer(), interest);
        assertTrue(interest.isEmpty());
        when(fixture.source().isMirrorMode()).thenReturn(true);
        assertTrue(MinecraftClientViewPortalAccess.ownable(fixture.source()));
        when(fixture.source().isMirrorMode()).thenReturn(false);
        when(fixture.source().getTunnelType()).thenReturn("LOCAL");
        boolean shared = FidelitySettings.sharedPlate;
        FidelitySettings.sharedPlate = false;
        try {
            assertTrue(portals.refused(fixture.peer(), fixture.source().getId()));
            assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        } finally {
            FidelitySettings.sharedPlate = shared;
        }
    }

    @Test
    public void geometryDescribesTheApertureAndFollowsTheObserverSide() {
        Fixture fixture = fixture(PortalType.PORTAL);
        when(fixture.source().isBlackoutBackground()).thenReturn(true);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        SessionPalette palette = new SessionPalette();
        ClientPortalGeometry geometry = portals.geometry(fixture.peer(), fixture.source().getId(), palette);
        assertNotNull(geometry);
        assertTrue(geometry.valid());
        assertEquals(3, geometry.apertureWidth());
        assertEquals(3, geometry.apertureHeight());
        assertEquals(9, geometry.openCellCount());
        assertTrue(geometry.frontSide());
        assertEquals(8, geometry.depthBlocks());
        assertEquals(ClientPortalGeometry.KIND_FRAME, geometry.kind());
        assertEquals(0L, geometry.targetIdentity());
        assertEquals(ClientPortalGeometry.BLACKOUT_SHELL, geometry.blackoutPolicy());
        assertEquals(BlockStateParser.serialize(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState()), palette.state(geometry.blackoutState()));
        long front = portals.geometryRevision(fixture.peer(), fixture.source().getId());
        assertEquals(front, portals.geometryRevision(fixture.peer(), fixture.source().getId()));
        when(fixture.player().getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, -4.0D));
        assertNotEquals(front, portals.geometryRevision(fixture.peer(), fixture.source().getId()));
        assertFalse(portals.geometry(fixture.peer(), fixture.source().getId(), palette).frontSide());
        ServerLevel elsewhere = mock(ServerLevel.class);
        when(fixture.player().level()).thenReturn(elsewhere);
        assertNull(portals.geometry(fixture.peer(), fixture.source().getId(), palette));
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
    }

    @Test
    public void sceneMarksOnlyTheObserversOwnProjectedEntity() {
        Fixture fixture = fixture(PortalType.PORTAL);
        MinecraftLocalEntityView entities = mock(MinecraftLocalEntityView.class);
        when(fixture.runtime().projections().scene(any(), any(), anyDouble())).thenReturn(entities);
        EntityVisual self = stand(fixture.peer().id(), 11.5D);
        EntityVisual other = stand(UUID.randomUUID(), 11.0D);
        when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(self, other));
        MinecraftClientViewScene scene = new MinecraftClientViewPortalAccess(fixture.runtime()).scene();
        List<EntityVisual> projected = scene.capture(fixture.peer(), fixture.source().getId(), 1L);
        assertEquals(2, projected.size());
        int observers = 0;
        for (EntityVisual visual : projected) {
            assertNotEquals(fixture.peer().id(), visual.id());
            observers += scene.isObserver(fixture.peer(), visual) ? 1 : 0;
        }
        assertEquals(1, observers);
    }

    private static EntityVisual stand(UUID id, double x) {
        return EntityVisual.full(id, "minecraft:armor_stand", x, 65.0D, -2.5D, 1.975D, 0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "",
            "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
    }

    private static Fixture fixture(PortalType type) {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftProjectorPortalAccess access = mock(MinecraftProjectorPortalAccess.class);
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        MinecraftRtpRuntime rtp = mock(MinecraftRtpRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerLevel world = mock(ServerLevel.class);
        when(runtime.server()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getViewDistance()).thenReturn(8);
        MinecraftPortal source = portal(0.0D);
        MinecraftPortal target = portal(10.0D);
        when(source.getType()).thenReturn(type);
        when(source.getNetworkViewLateralPad()).thenReturn(8);
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        ProjectionConfig projection = new ProjectionConfig();
        projection.maxProjectedCells = 10_000;
        WormholesSettings settings = new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new NetworkConfig());
        List<ViewPlateBuilder.Job<BlockState, ServerLevel>> scheduled = new ArrayList<>();
        ViewPlateCache<BlockState, ServerLevel> cache = new ViewPlateCache<>(FidelitySettings.plateMaxBytes, scheduled::add);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(runtime.portals()).thenReturn(registry);
        when(runtime.projections()).thenReturn(projections);
        when(runtime.rtp()).thenReturn(rtp);
        when(rtp.projectionDestination(any(), any())).thenReturn(target);
        when(rtp.knownDestination(any(), any())).thenReturn(target);
        when(projections.changes()).thenReturn(new ProjectionWorldChangeTracker());
        when(projections.plates()).thenReturn(cache);
        when(projections.view(world)).thenReturn(view);
        when(registry.get(source.getId())).thenReturn(source);
        when(registry.resolveLevel(any())).thenReturn(world);
        when(access.eligible(source)).thenReturn(true);
        when(access.current(source)).thenReturn(true);
        when(access.world(source)).thenReturn(world);
        when(access.world(target)).thenReturn(world);
        when(access.projectionDestination(source)).thenReturn(target);
        when(access.portals()).thenReturn(List.of());
        when(access.createRecursiveIndex()).thenAnswer(ignored -> new ProjectorRecursivePortals<>(access,
            () -> new ProjectorRecursivePortals.Options(0.75D, 64.0D)));
        when(view.getWorld()).thenReturn(world);
        when(view.worldId()).thenReturn(UUID.randomUUID());
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        BlockState stone = Blocks.STONE.defaultBlockState();
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        ServerPlayer player = viewer(world);
        MinecraftClientViewPeer peer = new MinecraftClientViewPeer(player.getUUID(), "Viewer", new Connection(PacketFlow.SERVERBOUND));
        peer.attach(player, access);
        return new Fixture(runtime, access, viewer(world), player, peer, source, view, rtp, cache, scheduled);
    }

    private static ServerPlayer viewer(ServerLevel world) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.requestedViewDistance()).thenReturn(8);
        when(player.level()).thenReturn(world);
        when(player.getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, 4.0D));
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static MinecraftPortal portal(double x) {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        PortalGeometry geometry = new PortalGeometry();
        geometry.setArea(new AxisAlignedBB(x, x + 2.999D, 64.0D, 66.999D, 0.0D, 0.999D));
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getGeometry()).thenReturn(geometry);
        when(portal.getFrame()).thenReturn(PortalFrame.canonical(Direction.S));
        when(portal.getOrigin()).thenReturn(new GeometryVector(x + 1.5D, 65.5D, 0.5D));
        when(portal.getNetworkViewDepth()).thenReturn(8);
        when(portal.getBlackoutColor()).thenReturn(BlackoutColor.BLACK);
        when(portal.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
        when(portal.getType()).thenReturn(PortalType.PORTAL);
        when(portal.isOpen()).thenReturn(true);
        return portal;
    }

    private record Fixture(WormholesModRuntime runtime, MinecraftProjectorPortalAccess access, ServerPlayer vanilla, ServerPlayer player,
                           MinecraftClientViewPeer peer, MinecraftPortal source, MinecraftProjectionWorldView view, MinecraftRtpRuntime rtp,
                           ViewPlateCache<BlockState, ServerLevel> cache, List<ViewPlateBuilder.Job<BlockState, ServerLevel>> scheduled) {
    }
}
