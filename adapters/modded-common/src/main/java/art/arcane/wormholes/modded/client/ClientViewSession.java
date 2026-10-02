package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.clientview.LocalPlateHandles;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewHandshake;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.PlateHandoff;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientViewSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesClientConfig config;
    private final ClientPalette palette;
    private final ClientPlateStore plates;
    private final ClientMeshSections meshes;
    private final Int2ObjectOpenHashMap<ClientPortal> portals;
    private final Int2ObjectOpenHashMap<ClientViewEnvironment> environments = new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet dirtyPortals;
    private final IntArrayList patchedBricks;
    private final int dataVersion;
    private final String brandTag;
    private volatile State state;
    private volatile boolean nativeSelected;
    private final Int2ObjectOpenHashMap<MeshFailure> meshFailures = new Int2ObjectOpenHashMap<>();
    private volatile long caps;
    private volatile ClientViewMessage.Offer offer;
    private volatile ClientViewMessage.Accept accept;
    private volatile ClientViewMessage.DeclineReason declineReason;
    private ClientViewMessage.ResetReason lastReset;
    private int resets;
    private long ignoredSceneMessages;
    private long protocolFailures;
    private boolean memoryFailureReported;

    public ClientViewSession(WormholesClientConfig config, ClientPalette palette, int dataVersion, String brandTag) {
        this.config = Objects.requireNonNull(config, "config");
        this.palette = Objects.requireNonNull(palette, "palette");
        this.plates = new ClientPlateStore(palette, config.plateMemoryBytes());
        this.meshes = new ClientMeshSections(palette, config.plateMemoryBytes());
        this.plates.otherMemory(meshes::bytes);
        this.meshes.otherMemory(plates::bytes);
        this.portals = new Int2ObjectOpenHashMap<>();
        this.dirtyPortals = new IntOpenHashSet();
        this.patchedBricks = new IntArrayList();
        this.dataVersion = dataVersion;
        this.brandTag = brandTag == null ? "" : brandTag;
        this.state = State.INIT;
        this.caps = ClientViewCapability.ALL;
    }

    public long clientCapabilities() {
        if (config.rendererMode() == WormholesClientConfig.Renderer.BLOCK_PACKETS) {
            return 0;
        }
        long capabilities = ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.BRICK_CACHE, ClientViewCapability.DEST_LIGHT,
            ClientViewCapability.ENTITY_FRAMES, ClientViewCapability.FX_EMITTERS, ClientViewCapability.ATMOSPHERE, ClientViewCapability.ZERO_COPY,
            ClientViewCapability.CONFIG_PHASE, ClientViewCapability.LINK_UNCOMPRESSED, ClientViewCapability.VIEW_STATS, ClientViewCapability.MESH_RENDER);
        if (config.clientMirror) {
            capabilities |= ClientViewCapability.CLIENT_MIRROR.mask();
        }
        if (config.clientRecursion) {
            capabilities |= ClientViewCapability.CLIENT_RECURSION.mask();
        }
        return capabilities;
    }

    public ClientViewMessage.Hello offer(ClientViewMessage.Offer received) {
        Objects.requireNonNull(received, "received");
        if (config.rendererMode() == WormholesClientConfig.Renderer.BLOCK_PACKETS) {
            nativeSelected = false;
            state = State.VANILLA;
            return null;
        }
        offer = received;
        state = State.OFFERED;
        return ClientViewHandshake.clientHello(received, dataVersion, clientCapabilities(), config.maxFrameBytes(),
            config.plateMemoryMbForHello(), LocalPlateHandles.nonce(), brandTag);
    }

    public void accept(ClientViewMessage.Accept received) {
        Objects.requireNonNull(received, "received");
        if (config.rendererMode() == WormholesClientConfig.Renderer.BLOCK_PACKETS) {
            nativeSelected = false;
            state = State.VANILLA;
            return;
        }
        accept = received;
        caps = ClientViewCapability.intersection(received.caps(), clientCapabilities());
        nativeSelected |= ClientViewCapability.MESH_RENDER.in(caps);
        state = nativeSelected && !ClientViewCapability.MESH_RENDER.in(caps) ? State.NATIVE_RECOVERING : State.CLIENT_VIEW;
    }

    public void decline(ClientViewMessage.Decline received) {
        Objects.requireNonNull(received, "received");
        declineReason = received.reason();
        state = nativeSelected ? State.NATIVE_RECOVERING : State.DECLINED;
    }

    public void unanswered() {
        if (state == State.OFFERED) {
            state = nativeSelected ? State.NATIVE_RECOVERING : State.VANILLA;
        }
    }

    public void abandon(Sink sink) {
        Objects.requireNonNull(sink, "sink");
        if (state != State.CLIENT_VIEW && state != State.OFFERED) {
            return;
        }
        state = nativeSelected ? State.NATIVE_RECOVERING : State.VANILLA;
        sink.reset(ClientViewMessage.ResetReason.PROTOCOL);
        clearPortals();
    }

    public boolean nativeSelected() {
        return nativeSelected;
    }

    public ClientViewMessage.Hello recoveryHello() {
        return state == State.NATIVE_RECOVERING && offer != null
            ? ClientViewHandshake.clientHello(offer, dataVersion, clientCapabilities(), config.maxFrameBytes(),
                config.plateMemoryMbForHello(), LocalPlateHandles.nonce(), brandTag)
            : null;
    }

    public MeshFailure meshFailure(int portalKey) {
        return meshFailures.get(portalKey);
    }

    public int unavailableMeshes() {
        return meshFailures.size();
    }

    public boolean handle(ClientViewMessage message, Sink sink) throws ClientViewProtocolException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(sink, "sink");
        if (message instanceof ClientViewMessage.Offer) {
            restart(sink);
            return true;
        }
        if (state == State.NATIVE_RECOVERING && message instanceof ClientViewMessage.SessionReset reset) {
            reset(reset.reason(), sink);
            return true;
        }
        if (state != State.CLIENT_VIEW) {
            return false;
        }
        if (nativeSelected && switch (message) {
            case ClientViewMessage.PlateBegin ignored -> true;
            case ClientViewMessage.PlateBricks ignored -> true;
            case ClientViewMessage.PlateEnd ignored -> true;
            case ClientViewMessage.PlatePatch ignored -> true;
            case ClientViewMessage.PlateHandle handle -> {
                LocalPlateHandles.take(handle.handle());
                yield true;
            }
            default -> false;
        }) {
            return true;
        }
        try {
            switch (message) {
                case ClientViewMessage.Palette paletteMessage -> palette.apply(paletteMessage);
                case ClientViewMessage.Portal portal -> portal(portal);
                case ClientViewMessage.PortalDrop drop -> drop(drop.portalKey(), sink);
                case ClientViewMessage.MeshBegin begin -> meshBegin(begin, sink);
                case ClientViewMessage.MeshSection section -> meshSection(section, sink);
                case ClientViewMessage.MeshDrop drop -> meshes.drop(drop.portalKey(), drop.generation(), drop.sectionX(), drop.sectionY(), drop.sectionZ());
                case ClientViewMessage.Environment environment -> {
                    if (portals.containsKey(environment.portalKey())) {
                        environments.put(environment.portalKey(), environment.environment());
                    } else {
                        ignoredSceneMessages++;
                    }
                }
                case ClientViewMessage.PlateBegin begin -> begin(begin, sink);
                case ClientViewMessage.PlateBricks bricks -> plates.bricks(bricks);
                case ClientViewMessage.PlateEnd end -> attach(plates.end(end));
                case ClientViewMessage.PlatePatch patch -> patch(patch);
                case ClientViewMessage.PlateHandle handle -> handle(handle);
                case ClientViewMessage.SessionReset reset -> reset(reset.reason(), sink);
                case ClientViewMessage.EntityFrame frame -> {
                    if (portals.containsKey(frame.portalKey())) {
                        sink.entities(frame);
                    } else {
                        ignoredSceneMessages++;
                    }
                }
                case ClientViewMessage.Fx fx -> {
                    if (fx.portalKey() == ClientViewProtocol.WORLD_FX_KEY || portals.containsKey(fx.portalKey())) {
                        sink.fx(fx);
                    } else {
                        ignoredSceneMessages++;
                    }
                }
                case ClientViewMessage.Atmosphere atmosphere -> {
                    if (portals.containsKey(atmosphere.portalKey())) {
                        sink.atmosphere(atmosphere);
                    } else {
                        ignoredSceneMessages++;
                    }
                }
                default -> throw new ClientViewProtocolException("unexpected clientbound " + message.type());
            }
            ClientViewMessage.PlateRefused refused = plates.takeRefusal();
            if (refused != null) {
                sink.refused(refused);
            }
        } catch (ClientViewProtocolException | RuntimeException failure) {
            protocolFailures++;
            throw failure;
        }
        return true;
    }

    public void refuseMesh(int portalKey, int generation, Sink sink) {
        Objects.requireNonNull(sink, "sink");
        ClientMeshSections.View view = meshes.view(portalKey);
        if (view == null || view.generation() != generation) {
            return;
        }
        meshes.remove(portalKey);
        meshFailures.put(portalKey, MeshFailure.MEMORY);
        if (!memoryFailureReported) {
            memoryFailureReported = true;
            LOGGER.warn("Native portal {} is unavailable because the section memory budget is exhausted; native streaming will retry", portalKey);
        }
        ClientPortal portal = portals.get(portalKey);
        if (portal != null) {
            sink.dropped(portal);
        }
        sink.refused(new ClientViewMessage.PlateRefused(portalKey, generation));
    }

    public void clearPlateContent() {
        plates.clear();
        dirtyPortals.clear();
        patchedBricks.clear();
        for (ClientPortal portal : portals.values()) {
            portal.clearContent();
        }
    }

    public void clearPortals() {
        meshFailures.clear();
        environments.clear();
        portals.clear();
        plates.clear();
        meshes.clear();
        dirtyPortals.clear();
    }

    public State state() {
        return state;
    }

    public boolean active() {
        return state == State.CLIENT_VIEW;
    }

    public long caps() {
        return caps;
    }

    public boolean has(ClientViewCapability capability) {
        return capability.in(caps);
    }

    public ClientViewMessage.Offer offerMessage() {
        return offer;
    }

    public ClientViewMessage.Accept acceptMessage() {
        return accept;
    }

    public ClientViewMessage.DeclineReason declineReason() {
        return declineReason;
    }

    public ClientViewMessage.ResetReason lastReset() {
        return lastReset;
    }

    public int resets() {
        return resets;
    }

    public long ignoredSceneMessages() {
        return ignoredSceneMessages;
    }

    public long protocolFailures() {
        return protocolFailures;
    }

    public ClientPalette palette() {
        return palette;
    }

    public int memoryMb() {
        return (int) Math.min(65535L, (plates.bytes() + meshes.bytes() + 1048575L) / 1048576L);
    }

    public ClientMeshSections meshes() {
        return meshes;
    }

    public ClientViewEnvironment environment(int portalKey) {
        return environments.get(portalKey);
    }

    public ClientPlateStore plates() {
        return plates;
    }

    public Int2ObjectOpenHashMap<ClientPortal> portals() {
        return portals;
    }

    public ClientPortal portal(int portalKey) {
        return portals.get(portalKey);
    }

    public IntOpenHashSet dirtyPortals() {
        return dirtyPortals;
    }

    public WormholesClientConfig config() {
        return config;
    }

    public int dataVersion() {
        return dataVersion;
    }

    private void portal(ClientViewMessage.Portal message) throws ClientViewProtocolException {
        if (!message.geometry().valid()) {
            throw new ClientViewProtocolException("invalid geometry for portal " + message.portalKey());
        }
        ClientPortal portal = portals.get(message.portalKey());
        if (portal == null) {
            portals.put(message.portalKey(), new ClientPortal(message.portalKey(), message.geometry(), message.geometryRevision(),
                config.hysteresisBlocks));
            return;
        }
        portal.geometry(message.geometry(), message.geometryRevision());
        if (portal.contentDirty()) {
            dirtyPortals.add(portal.portalKey());
        }
    }

    private void drop(int portalKey, Sink sink) {
        meshFailures.remove(portalKey);
        environments.remove(portalKey);
        ClientPortal portal = portals.remove(portalKey);
        plates.drop(portalKey);
        meshes.remove(portalKey);
        dirtyPortals.remove(portalKey);
        if (portal != null) {
            sink.dropped(portal);
        }
    }

    private void meshBegin(ClientViewMessage.MeshBegin message, Sink sink) throws ClientViewProtocolException {
        ClientPortal portal = portals.get(message.portalKey());
        if (portal == null) {
            ignoredSceneMessages++;
            return;
        }
        if (meshes.begin(message.portalKey(), message.generation(), message.bounds(), message.maxResidentSections())) {
            meshFailures.remove(message.portalKey());
            environments.remove(message.portalKey());
            sink.meshStarted(portal);
            plates.drop(message.portalKey());
        }
    }

    private void meshSection(ClientViewMessage.MeshSection message, Sink sink) throws ClientViewProtocolException {
        ClientMeshSections.Result result = meshes.put(message);
        if (result == ClientMeshSections.Result.REFUSED) {
            refuseMesh(message.portalKey(), message.generation(), sink);
        } else if (result == ClientMeshSections.Result.APPLIED || result == ClientMeshSections.Result.DUPLICATE) {
            sink.meshAck(new ClientViewMessage.MeshAck(message.portalKey(), message.generation(), message.sectionX(), message.sectionY(),
                message.sectionZ(), message.revision()));
        }
    }

    private void begin(ClientViewMessage.PlateBegin begin, Sink sink) throws ClientViewProtocolException {
        ClientViewMessage.BrickMiss.Plate miss = plates.begin(begin);
        if (miss != null && has(ClientViewCapability.BRICK_CACHE)) {
            sink.brickMiss(miss);
        }
    }

    private void handle(ClientViewMessage.PlateHandle message) {
        PlateHandoff<BlockState> handoff = LocalPlateHandles.take(message.handle());
        attach(plates.handle(message, handoff));
    }

    private void attach(ClientPlate plate) {
        ClientPortal portal = owner(plate);
        if (portal != null) {
            portal.plate(plate);
        }
    }

    private void patch(ClientViewMessage.PlatePatch message) throws ClientViewProtocolException {
        patchedBricks.clear();
        ClientPlate plate = plates.patch(message, patchedBricks);
        ClientPortal portal = owner(plate);
        if (portal != null) {
            portal.patch(plate, patchedBricks);
        }
    }

    private ClientPortal owner(ClientPlate plate) {
        if (plate == null) {
            return null;
        }
        ClientPortal portal = portals.get(plate.portalKey());
        if (portal == null) {
            plates.drop(plate.portalKey());
            return null;
        }
        dirtyPortals.add(portal.portalKey());
        return portal;
    }

    private void restart(Sink sink) {
        sink.restarted();
        clearPortals();
        palette.reset();
    }

    private void reset(ClientViewMessage.ResetReason reason, Sink sink) {
        lastReset = reason;
        resets++;
        sink.reset(reason);
        clearPortals();
        if (reason == ClientViewMessage.ResetReason.DISABLED) {
            nativeSelected = false;
            state = State.VANILLA;
        } else if (reason == ClientViewMessage.ResetReason.PROTOCOL || reason == ClientViewMessage.ResetReason.OVERLOAD) {
            state = nativeSelected ? State.CLIENT_VIEW : State.VANILLA;
        }
    }

    public enum MeshFailure {
        MEMORY
    }

    public enum State {
        INIT,
        OFFERED,
        CLIENT_VIEW,
        NATIVE_RECOVERING,
        VANILLA,
        DECLINED
    }

    public interface Sink {
        void meshStarted(ClientPortal portal);

        void meshAck(ClientViewMessage.MeshAck ack);

        void brickMiss(ClientViewMessage.BrickMiss.Plate plate);

        void refused(ClientViewMessage.PlateRefused refused);

        void dropped(ClientPortal portal);

        void reset(ClientViewMessage.ResetReason reason);

        void restarted();

        void entities(ClientViewMessage.EntityFrame frame);

        void fx(ClientViewMessage.Fx fx);

        void atmosphere(ClientViewMessage.Atmosphere atmosphere);
    }
}
