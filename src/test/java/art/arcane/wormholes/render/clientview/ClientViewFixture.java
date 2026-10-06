package art.arcane.wormholes.render.clientview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.mockito.MockedStatic;

import com.github.retrooper.packetevents.protocol.ConnectionState;

import art.arcane.wormholes.config.toml.ClientViewConfig;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewChannel;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.MirrorRotation;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.client.session.ClientViewInbound;
import art.arcane.wormholes.render.client.session.ClientViewOptions;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

final class ClientViewFixture implements AutoCloseable {
    static final int DATA_VERSION = 4555;
    static final long CLIENT_CAPS = ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.DEST_LIGHT,
        ClientViewCapability.ENTITY_FRAMES, ClientViewCapability.CONFIG_PHASE, ClientViewCapability.VIEW_STATS);

    final ClientViewPacketEvents packets = new ClientViewPacketEvents();
    final MockedStatic<Bukkit> bukkit;
    final World world = mock(World.class);
    final ProjectionWorldView view = mock(ProjectionWorldView.class, withSettings().extraInterfaces(ProjectionEntityView.class));
    final ILocalPortal portal = mock(ILocalPortal.class);
    final Player player = mock(Player.class);
    final UUID playerId = UUID.randomUUID();
    final ClientViewPacketEvents.RecordingUser user;
    final List<String> released = new CopyOnWriteArrayList<String>();
    final List<ViewPlateBuilder.Job<BlockData, World>> jobs = new ArrayList<ViewPlateBuilder.Job<BlockData, World>>();
    final List<Long> expiries = new ArrayList<Long>();
    final List<Runnable> expiryTasks = new ArrayList<Runnable>();
    final List<String> verbose = new CopyOnWriteArrayList<String>();
    final ViewPlateCache<BlockData, World> plates;
    final ProjectionWorldViewProvider views;
    final BukkitClientView clientView;
    final BukkitClientViewNegotiator negotiator;
    final Location eye;
    private final boolean oldSharedPlate = FidelitySettings.sharedPlate;
    private final boolean oldBlockEntities = FidelitySettings.blockEntities;
    private final AtmosphereMode oldAtmosphere = FidelitySettings.atmosphereModeDefault;
    long caps;
    long tick;

    ClientViewFixture(ClientViewOptions options, ConnectionState state) {
        FidelitySettings.sharedPlate = true;
        FidelitySettings.blockEntities = false;
        FidelitySettings.atmosphereModeDefault = AtmosphereMode.OFF;
        BlockData stone = block(Material.STONE, "minecraft:stone");
        BlockData air = block(Material.AIR, "minecraft:air");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.createBlockData(anyString())).thenReturn(stone);
        bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenAnswer(call -> call.getArgument(0) == Material.AIR ? air : stone);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(Map.of("worldKey", "minecraft:overworld", "x1", 0, "x2", 2,
            "y1", 64, "y2", 66, "z1", 0, "z2", 0)));
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getWorld()).thenReturn(world);
        when(portal.getName()).thenReturn("clientview mirror");
        when(portal.getFrame()).thenReturn(PortalFrame.canonical(Direction.N));
        when(portal.getOrigin()).thenReturn(BukkitGeometry.vector(structure.getCenter()));
        when(portal.getStructure()).thenReturn(structure);
        when(portal.isOpen()).thenReturn(true);
        when(portal.isMirrorMode()).thenReturn(true);
        when(portal.getMirrorRotation()).thenReturn(MirrorRotation.DEGREES_0);
        when(portal.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
        when(portal.getNetworkViewDepth()).thenReturn(8);
        when(portal.getNetworkViewLateralPad()).thenReturn(4);
        eye = new Location(world, 1.0D, 65.0D, 4.0D);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Observer");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenAnswer(call -> eye.clone());
        when(player.getClientViewDistance()).thenReturn(16);
        when(view.getWorld()).thenReturn(world);
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        when(view.getRevision()).thenReturn(1L);
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call -> ((Integer) call.getArgument(1)) < 64 ? stone : air);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenAnswer(call -> ((Integer) call.getArgument(1)) < 64 ? Material.STONE : Material.AIR);
        views = new ProjectionWorldViewProvider() {
            @Override
            public ProjectionWorldView view(World ignored) {
                return view;
            }

            @Override
            public boolean usesRegionSnapshots() {
                return true;
            }
        };
        plates = new ViewPlateCache<BlockData, World>(1L << 30, jobs::add);
        user = packets.user(playerId, "Observer", state);
        user.owner = player;
        clientView = new BukkitClientView(new BukkitClientView.Options(views, plates, id -> id.equals(portal.getId()) ? portal : null,
            (observerId, portalId) -> released.add(observerId + " " + portalId), Runnable::run, DATA_VERSION, options, ignored -> user,
            (target, task, delayTicks) -> {
                expiries.add(Long.valueOf(delayTicks));
                expiryTasks.add(task);
            }, Logger.getLogger("clientview-test"), verbose::add, false));
        negotiator = clientView.negotiator();
    }

    static ClientViewOptions options(boolean enabled, boolean brickCache, int helloGraceMillis) {
        ClientViewConfig config = new ClientViewConfig();
        config.enabled = enabled;
        config.brickCache = brickCache;
        config.helloGraceMillis = helloGraceMillis;
        return ClientViewOptions.from(config, 5);
    }

    ClientViewServerSession<ClientViewObserver, BlockData> session() {
        return clientView.registry().session(playerId);
    }

    ClientViewInbound hello() throws ClientViewProtocolException {
        return hello(CLIENT_CAPS);
    }

    ClientViewInbound hello(long clientCaps) throws ClientViewProtocolException {
        return c2s(new ClientViewMessage.Hello(ClientViewProtocol.WIRE_VERSION, DATA_VERSION, clientCaps, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES,
            256, 0L, "fabric"));
    }

    ILocalPortal linkedPortal(int z) {
        ILocalPortal linked = mock(ILocalPortal.class);
        ILocalPortal destination = mock(ILocalPortal.class);
        ITunnel tunnel = mock(ITunnel.class);
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(Map.of("worldKey", "minecraft:overworld", "x1", 0, "x2", 2, "y1", 64, "y2", 66, "z1", z, "z2", z)));
        when(linked.getId()).thenReturn(UUID.randomUUID());
        when(linked.getWorld()).thenReturn(world);
        when(linked.getName()).thenReturn("clientview linked");
        when(linked.getFrame()).thenReturn(PortalFrame.canonical(Direction.N));
        when(linked.getOrigin()).thenReturn(BukkitGeometry.vector(structure.getCenter()));
        when(linked.getStructure()).thenReturn(structure);
        when(linked.isOpen()).thenReturn(true);
        when(linked.getTunnel()).thenReturn(tunnel);
        when(linked.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
        when(linked.getNetworkViewDepth()).thenReturn(8);
        when(linked.getNetworkViewLateralPad()).thenReturn(4);
        when(tunnel.getDestination()).thenReturn(destination);
        when(destination.getId()).thenReturn(UUID.randomUUID());
        when(destination.getWorld()).thenReturn(world);
        when(destination.getFrame()).thenReturn(PortalFrame.canonical(Direction.S));
        when(destination.getOrigin()).thenReturn(new GeometryVector(101.4995D, 65.4995D, 100.4995D));
        return linked;
    }

    List<ILocalPortal> routeWith(ILocalPortal nested) {
        List<ILocalPortal> interested = new ArrayList<ILocalPortal>(List.of(portal));
        clientView.route(player, eye.clone(), interested, List.of(portal, nested), Map.of(), ++tick);
        return interested;
    }

    ClientViewInbound c2s(ClientViewMessage message) throws ClientViewProtocolException {
        byte[] payload = ClientViewCodec.encodeC2S(message);
        return session().receive(payload, 0, payload.length);
    }

    List<ClientViewPacketEvents.Sent> drain() {
        return user.drain();
    }

    List<ClientViewMessage> messages() throws ClientViewProtocolException {
        List<ClientViewMessage> messages = new ArrayList<ClientViewMessage>();
        for (ClientViewPacketEvents.Sent sent : drain()) {
            if (!ClientViewChannel.CHANNEL.equals(sent.channel())) {
                continue;
            }
            ClientViewMessage message = ClientViewCodec.decodeS2C(sent.data(), caps == 0L ? ClientViewCapability.ALL : caps).message();
            if (message instanceof ClientViewMessage.Accept accept) {
                caps = accept.caps();
            }
            messages.add(message);
        }
        return messages;
    }

    List<ILocalPortal> route() {
        List<ILocalPortal> interested = new ArrayList<ILocalPortal>(List.of(portal));
        clientView.route(player, eye.clone(), interested, List.of(portal), Map.of(), ++tick);
        return interested;
    }

    void buildPlates() {
        List<ViewPlateBuilder.Job<BlockData, World>> pending = new ArrayList<ViewPlateBuilder.Job<BlockData, World>>(jobs);
        jobs.clear();
        for (ViewPlateBuilder.Job<BlockData, World> job : pending) {
            while (!job.step(Integer.MAX_VALUE)) {
                Thread.onSpinWait();
            }
            plates.publish(job, job.result());
        }
    }

    @Override
    public void close() {
        clientView.shutdown();
        bukkit.close();
        packets.close();
        FidelitySettings.sharedPlate = oldSharedPlate;
        FidelitySettings.blockEntities = oldBlockEntities;
        FidelitySettings.atmosphereModeDefault = oldAtmosphere;
    }

    private static BlockData block(Material material, String state) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        when(data.getAsString()).thenReturn(state);
        when(data.clone()).thenReturn(data);
        return data;
    }
}
