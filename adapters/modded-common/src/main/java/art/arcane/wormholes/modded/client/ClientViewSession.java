package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.clientview.LocalPlateHandles;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.stream.ViewStreamHandshake;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.PlateHandoff;
import art.arcane.optics.aperture.ApertureDescriptor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.ChatFormatting;

import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.network.client.FxMessage;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewExtensions;
import art.arcane.wormholes.portal.ApertureKind;

public final class ClientViewSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesClientConfig config;
    private final ClientPalette palette;
    private final ClientPlateStore plates;
    private final ClientMeshSections meshes;
    private final Int2ObjectOpenHashMap<ClientPortal> portals;
    private final Int2ObjectOpenHashMap<ApertureDescriptor> meshGeometry = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<ProjectionEnvironment> environments = new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet dirtyPortals;
    private final Int2ObjectOpenHashMap<List<ViewStreamMessage.MeshClaim>> pendingClaims = new Int2ObjectOpenHashMap<>();
    private final Int2IntOpenHashMap cacheSequences = new Int2IntOpenHashMap();
    private final IntArrayList patchedBricks;
    private final int dataVersion;
    private final String brandTag;
    private volatile State state;
    private volatile boolean nativeSelected;
    private final Int2ObjectOpenHashMap<MeshFailure> meshFailures = new Int2ObjectOpenHashMap<>();
    private volatile long caps;
    private volatile ViewStreamMessage.Offer offer;
    private volatile ViewStreamMessage.Accept accept;
    private volatile ViewStreamMessage.DeclineReason declineReason;
    private ViewStreamMessage.ResetReason lastReset;
    private int resets;
    private long ignoredSceneMessages;
    private long protocolFailures;
    private boolean memoryFailureReported;
    private UUID selfEntityId;

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
        this.caps = ViewStreamCapability.ALL;
    }

    public long clientCapabilities() {
        if (config.rendererMode() == WormholesClientConfig.Renderer.BLOCK_PACKETS) {
            return 0;
        }
        long capabilities = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE, ViewStreamCapability.DEST_LIGHT,
            ViewStreamCapability.ENTITY_FRAMES, ViewStreamCapability.ENTITY_EVENTS, ViewStreamCapability.ATMOSPHERE, ViewStreamCapability.ZERO_COPY,
            ViewStreamCapability.CONFIG_PHASE, ViewStreamCapability.LINK_UNCOMPRESSED, ViewStreamCapability.VIEW_STATS, ViewStreamCapability.MESH_RENDER,
            ViewStreamCapability.LOCAL_MESH, ViewStreamCapability.MESH_REUSE, ViewStreamCapability.ENTITY_SELF)
            | ClientViewExtensions.FX_EMITTERS | ClientViewExtensions.PREPARED_TRAVEL | ClientViewExtensions.PREPARED_TRAVEL_CACHE
            | ClientViewExtensions.REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL;
        if (config.clientMirror) {
            capabilities |= ViewStreamCapability.CLIENT_MIRROR.mask();
        }
        if (config.clientRecursion) {
            capabilities |= ViewStreamCapability.CLIENT_RECURSION.mask();
        }
        return capabilities;
    }

    public ViewStreamMessage.Hello offer(ViewStreamMessage.Offer received) {
        Objects.requireNonNull(received, "received");
        if (config.rendererMode() == WormholesClientConfig.Renderer.BLOCK_PACKETS) {
            nativeSelected = false;
            state = State.VANILLA;
            return null;
        }
        offer = received;
        declineReason = null;
        state = State.OFFERED;
        return ViewStreamHandshake.clientHello(received, dataVersion, clientCapabilities(), config.maxFrameBytes(),
            config.plateMemoryMbForHello(), LocalPlateHandles.nonce(), brandTag);
    }

    public void accept(ViewStreamMessage.Accept received) {
        Objects.requireNonNull(received, "received");
        if (config.rendererMode() == WormholesClientConfig.Renderer.BLOCK_PACKETS) {
            nativeSelected = false;
            state = State.VANILLA;
            return;
        }
        accept = received;
        declineReason = null;
        caps = ViewStreamCapability.intersection(received.caps(), clientCapabilities());
        if (!ViewStreamCapability.ENTITY_SELF.in(caps)) {
            selfEntityId = null;
        }
        nativeSelected |= ViewStreamCapability.MESH_RENDER.in(caps);
        state = nativeSelected && !ViewStreamCapability.MESH_RENDER.in(caps) ? State.NATIVE_RECOVERING : State.CLIENT_VIEW;
    }

    public UUID selfEntityId() {
        return selfEntityId;
    }

    public void decline(ViewStreamMessage.Decline received) {
        Objects.requireNonNull(received, "received");
        selfEntityId = null;
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
        selfEntityId = null;
        sink.reset(ViewStreamMessage.ResetReason.PROTOCOL);
        clearPortals();
    }

    public boolean nativeSelected() {
        return nativeSelected;
    }

    public ViewStreamMessage.Hello recoveryHello() {
        return state == State.NATIVE_RECOVERING && offer != null
            ? ViewStreamHandshake.clientHello(offer, dataVersion, clientCapabilities(), config.maxFrameBytes(),
                config.plateMemoryMbForHello(), LocalPlateHandles.nonce(), brandTag)
            : null;
    }

    public MeshFailure meshFailure(int portalKey) {
        return meshFailures.get(portalKey);
    }

    public int unavailableMeshes() {
        return meshFailures.size();
    }

    public boolean handle(ViewStreamMessage message, Sink sink) throws ViewStreamProtocolException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(sink, "sink");
        if (message instanceof ViewStreamMessage.Offer) {
            restart(sink);
            return true;
        }
        if (state == State.NATIVE_RECOVERING && message instanceof ViewStreamMessage.SessionReset reset) {
            reset(reset.reason(), sink);
            return true;
        }
        if (state != State.CLIENT_VIEW) {
            return false;
        }
        meshes.epoch(accept.hashSalt());
        if (nativeSelected && switch (message) {
            case ViewStreamMessage.PlateBegin ignored -> true;
            case ViewStreamMessage.PlateBricks ignored -> true;
            case ViewStreamMessage.PlateEnd ignored -> true;
            case ViewStreamMessage.PlatePatch ignored -> true;
            case ViewStreamMessage.PlateHandle handle -> {
                LocalPlateHandles.take(handle.handle());
                yield true;
            }
            default -> false;
        }) {
            return true;
        }
        try {
            switch (message) {
                case ViewStreamMessage.Extension extension -> extension(extension, sink);
                case ViewStreamMessage.Palette paletteMessage -> palette.apply(paletteMessage);
                case ViewStreamMessage.Portal portal -> portal(portal);
                case ViewStreamMessage.PortalDrop drop -> drop(drop.portalKey(), sink);
                case ViewStreamMessage.MeshBegin begin -> meshBegin(begin, sink);
                case ViewStreamMessage.MeshSection section -> meshSection(section, sink);
                case ViewStreamMessage.MeshReuse reuse -> {
                    if (has(ViewStreamCapability.MESH_REUSE)) {
                        ClientMeshSections.Result result = meshes.reuse(reuse);
                        if (result == ClientMeshSections.Result.DUPLICATE) {
                            sink.meshAck(new ViewStreamMessage.MeshAck(reuse.portalKey(), reuse.generation(), reuse.sectionX(), reuse.sectionY(), reuse.sectionZ(), reuse.revision()));
                        }
                    }
                }
                case ViewStreamMessage.MeshDrop drop -> meshes.drop(drop.portalKey(), drop.generation(), drop.sectionX(), drop.sectionY(), drop.sectionZ());
                case ViewStreamMessage.Environment environment -> {
                    if (portals.containsKey(environment.portalKey())) {
                        environments.put(environment.portalKey(), environment.environment());
                        if (has(ViewStreamCapability.MESH_REUSE)) {
                            long target = portals.get(environment.portalKey()).geometry().targetIdentity();
                            ClientMeshSections.Identity binding = new ClientMeshSections.Identity(environment.environment(), accept.hashSalt(), target);
                            ClientMeshSections.View view = meshes.view(environment.portalKey());
                            ClientMeshSections.Identity previous = view == null ? null : view.identity();
                            if (previous != null && !previous.equals(binding)) {
                                pendingClaims.remove(environment.portalKey());
                            }
                            List<ViewStreamMessage.MeshClaim> claims = meshes.bind(environment.portalKey(), binding);
                            cacheClaims(environment.portalKey(), claims);
                        }
                    } else {
                        ignoredSceneMessages++;
                    }
                }
                case ViewStreamMessage.PlateBegin begin -> begin(begin, sink);
                case ViewStreamMessage.PlateBricks bricks -> plates.bricks(bricks);
                case ViewStreamMessage.PlateEnd end -> attach(plates.end(end));
                case ViewStreamMessage.PlatePatch patch -> patch(patch);
                case ViewStreamMessage.PlateHandle handle -> handle(handle);
                case ViewStreamMessage.SessionReset reset -> reset(reset.reason(), sink);
                case ViewStreamMessage.EntityEvent event -> {
                    if (portals.containsKey(event.portalKey())) {
                        sink.entityEvent(event);
                    } else {
                        ignoredSceneMessages++;
                    }
                }
                case ViewStreamMessage.EntitySelf self -> {
                    if (ViewStreamCapability.ENTITY_SELF.in(caps)) {
                        selfEntityId = self.projectedId();
                    }
                }
                case ViewStreamMessage.EntityFrame frame -> {
                    if (portals.containsKey(frame.portalKey())) {
                        sink.entities(frame);
                    } else {
                        ignoredSceneMessages++;
                    }
                }
                case ViewStreamMessage.Atmosphere atmosphere -> {
                    if (portals.containsKey(atmosphere.portalKey())) {
                        sink.atmosphere(atmosphere);
                    } else {
                        ignoredSceneMessages++;
                    }
                }
                default -> throw new ViewStreamProtocolException("unexpected clientbound " + MinecraftClientViewExtensions.CODEC.name(message));
            }
            ViewStreamMessage.PlateRefused refused = plates.takeRefusal();
            if (refused != null) {
                sink.refused(refused);
            }
        } catch (ViewStreamProtocolException | RuntimeException failure) {
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
        sink.refused(new ViewStreamMessage.PlateRefused(portalKey, generation));
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
        cacheSequences.clear();
        pendingClaims.clear();
        meshGeometry.clear();
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

    public boolean managesVanillaPortal(int x, int y, int z) {
        if (!active()) {
            return false;
        }
        for (ClientPortal portal : portals.values()) {
            ApertureDescriptor geometry = portal.geometry();
            if (!portal.nested() && geometry.kind() == ApertureKind.VANILLA_REPLACEMENT
                && geometry.containsCell(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public ConnectionStatus connectionStatus() {
        if (active()) {
            return ConnectionStatus.CONNECTED;
        }
        ViewStreamMessage.Offer current = offer;
        if (declineReason == ViewStreamMessage.DeclineReason.WIRE_MISMATCH
            || declineReason == ViewStreamMessage.DeclineReason.DATA_VERSION_MISMATCH
            || current != null && (current.wire() != ViewStreamLimits.WIRE_VERSION || current.mcDataVersion() != dataVersion)) {
            return ConnectionStatus.MISMATCH;
        }
        return ConnectionStatus.DISCONNECTED;
    }

    public long caps() {
        return caps;
    }

    public boolean has(ViewStreamCapability capability) {
        return capability.in(caps);
    }

    public boolean has(long capability) {
        return (caps & capability) != 0L;
    }

    public ViewStreamMessage.Accept acceptMessage() {
        return accept;
    }

    public ViewStreamMessage.DeclineReason declineReason() {
        return declineReason;
    }

    public ViewStreamMessage.ResetReason lastReset() {
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

    public void cacheClaims(int portalKey, List<ViewStreamMessage.MeshClaim> claims) {
        if (!claims.isEmpty() && meshes.view(portalKey) != null && has(ViewStreamCapability.MESH_REUSE)) {
            pendingClaims.computeIfAbsent(portalKey, ignored -> new ArrayList<>()).addAll(claims);
        }
    }

    public void flushCached(Consumer<ViewStreamMessage> sender) {
        if (!active() || !has(ViewStreamCapability.MESH_REUSE)) {
            return;
        }
        int remaining = 4;
        for (int key : pendingClaims.keySet().toIntArray()) {
            ClientMeshSections.View view = meshes.view(key);
            List<ViewStreamMessage.MeshClaim> claims = pendingClaims.get(key);
            if (view == null) {
                pendingClaims.remove(key);
                continue;
            }
            if (remaining-- == 0) {
                break;
            }
            int count = Math.min(claims.size(), ViewStreamMessage.MeshCached.MAX_CLAIMS);
            sender.accept(new ViewStreamMessage.MeshCached(key, view.generation(), nextCacheSequence(key), true, claims.subList(0, count)));
            claims.subList(0, count).clear();
            if (claims.isEmpty()) {
                pendingClaims.remove(key);
            }
        }
    }

    public ClientMeshSections meshes() {
        return meshes;
    }

    public ProjectionEnvironment environment(int portalKey) {
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

    private void extension(ViewStreamMessage.Extension extension, Sink sink) throws ViewStreamProtocolException {
        switch (extension.payload()) {
            case TravelMessage ignored -> {
            }
            case FxMessage.Fx fx -> {
                if (fx.portalKey() == FxMessage.WORLD_FX_KEY || portals.containsKey(fx.portalKey())) {
                    sink.fx(fx);
                } else {
                    ignoredSceneMessages++;
                }
            }
            default -> throw new ViewStreamProtocolException("unexpected clientbound extension " + extension.id());
        }
    }

    private int nextCacheSequence(int portalKey) {
        int sequence = cacheSequences.get(portalKey) + 1;
        cacheSequences.put(portalKey, sequence);
        return sequence;
    }

    private void portal(ViewStreamMessage.Portal message) throws ViewStreamProtocolException {
        if (!message.geometry().valid()) {
            throw new ViewStreamProtocolException("invalid geometry for portal " + message.portalKey());
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
        cacheSequences.remove(portalKey);
        pendingClaims.remove(portalKey);
        meshFailures.remove(portalKey);
        meshGeometry.remove(portalKey);
        environments.remove(portalKey);
        ClientPortal portal = portals.remove(portalKey);
        plates.drop(portalKey);
        meshes.remove(portalKey);
        dirtyPortals.remove(portalKey);
        if (portal != null) {
            sink.dropped(portal);
        }
    }

    private void meshBegin(ViewStreamMessage.MeshBegin message, Sink sink) throws ViewStreamProtocolException {
        ClientPortal portal = portals.get(message.portalKey());
        if (portal == null) {
            ignoredSceneMessages++;
            return;
        }
        ApertureDescriptor previous = meshGeometry.get(message.portalKey());
        boolean retain = previous != null && previous.sameContentSurface(portal.geometry()) && portal.geometry().mirror()
            && has(ViewStreamCapability.LOCAL_MESH) && environments.containsKey(message.portalKey());
        boolean begun = retain
            ? meshes.retainLocal(message.portalKey(), message.generation(), message.bounds(), message.maxResidentSections())
            : meshes.begin(message.portalKey(), message.generation(), message.bounds(), message.maxResidentSections());
        if (begun) {
            cacheSequences.remove(message.portalKey());
            pendingClaims.remove(message.portalKey());
            meshGeometry.put(message.portalKey(), portal.geometry());
            meshFailures.remove(message.portalKey());
            if (!retain) {
                environments.remove(message.portalKey());
            }
            sink.meshStarted(portal);
            plates.drop(message.portalKey());
        }
    }

    private void meshSection(ViewStreamMessage.MeshSection message, Sink sink) throws ViewStreamProtocolException {
        ClientMeshSections.Result result = meshes.put(message);
        if (result == ClientMeshSections.Result.REFUSED) {
            refuseMesh(message.portalKey(), message.generation(), sink);
        } else if (result == ClientMeshSections.Result.APPLIED || result == ClientMeshSections.Result.DUPLICATE) {
            sink.meshAck(new ViewStreamMessage.MeshAck(message.portalKey(), message.generation(), message.sectionX(), message.sectionY(),
                message.sectionZ(), message.revision()));
        }
    }

    private void begin(ViewStreamMessage.PlateBegin begin, Sink sink) throws ViewStreamProtocolException {
        ViewStreamMessage.BrickMiss.Plate miss = plates.begin(begin);
        if (miss != null && has(ViewStreamCapability.BRICK_CACHE)) {
            sink.brickMiss(miss);
        }
    }

    private void handle(ViewStreamMessage.PlateHandle message) {
        PlateHandoff<BlockState> handoff = LocalPlateHandles.take(message.handle());
        attach(plates.handle(message, handoff));
    }

    private void attach(ClientPlate plate) {
        ClientPortal portal = owner(plate);
        if (portal != null) {
            portal.plate(plate);
        }
    }

    private void patch(ViewStreamMessage.PlatePatch message) throws ViewStreamProtocolException {
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
        selfEntityId = null;
        sink.restarted();
        clearPortals();
        palette.reset();
    }

    private void reset(ViewStreamMessage.ResetReason reason, Sink sink) {
        selfEntityId = null;
        lastReset = reason;
        resets++;
        sink.reset(reason);
        clearPortals();
        if (reason == ViewStreamMessage.ResetReason.DISABLED) {
            nativeSelected = false;
            state = State.VANILLA;
        } else if (reason == ViewStreamMessage.ResetReason.PROTOCOL || reason == ViewStreamMessage.ResetReason.OVERLOAD) {
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

    public enum ConnectionStatus {
        CONNECTED("Connected", ChatFormatting.GREEN),
        MISMATCH("Mismatch", ChatFormatting.YELLOW),
        DISCONNECTED("Disconnected", ChatFormatting.RED);

        private final String debugLine;

        ConnectionStatus(String label, ChatFormatting color) {
            debugLine = ChatFormatting.GOLD + "Wormholes: " + color + label + ChatFormatting.RESET;
        }

        public String debugLine() {
            return debugLine;
        }
    }

    public interface Sink {
        void meshStarted(ClientPortal portal);

        void meshAck(ViewStreamMessage.MeshAck ack);

        void brickMiss(ViewStreamMessage.BrickMiss.Plate plate);

        void refused(ViewStreamMessage.PlateRefused refused);

        void dropped(ClientPortal portal);

        void reset(ViewStreamMessage.ResetReason reason);

        void restarted();

        void entities(ViewStreamMessage.EntityFrame frame);

        default void entityEvent(ViewStreamMessage.EntityEvent event) {
        }

        void fx(FxMessage.Fx fx);

        void atmosphere(ViewStreamMessage.Atmosphere atmosphere);
    }
}
