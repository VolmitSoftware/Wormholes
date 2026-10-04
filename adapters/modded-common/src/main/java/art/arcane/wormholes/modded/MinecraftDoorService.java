package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.PocketsConfig;
import art.arcane.wormholes.door.PocketRules;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.door.PocketBinding;
import art.arcane.wormholes.door.PocketDoorDestination;
import art.arcane.wormholes.door.PocketInstances;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketRooms;
import art.arcane.wormholes.door.PocketRoom;
import art.arcane.wormholes.door.PocketRole;
import art.arcane.wormholes.door.PocketRosterService;
import art.arcane.wormholes.door.PocketShell;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.wormholes.door.PocketEntryCoordinates;
import art.arcane.wormholes.door.ReturnTicket;
import art.arcane.wormholes.chunk.presend.ChunkPreSendTicket;
import art.arcane.wormholes.door.DoorAccessPolicy;
import art.arcane.wormholes.door.DoorArrivals;
import art.arcane.wormholes.door.DoorAutoCloseBook;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorHalf;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorOpenCycle;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPairIdentity;
import art.arcane.wormholes.door.DoorPlanePairing;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.DoorTransit;
import art.arcane.wormholes.door.DoorTransitGate;
import art.arcane.wormholes.door.DoorTravelerClass;
import art.arcane.wormholes.door.DoorTravelerPolicy;
import art.arcane.wormholes.door.DoorVec3;
import art.arcane.wormholes.door.DoorVelocityTransform;
import art.arcane.wormholes.door.DoorwayCrossing;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PairEndpoint;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import art.arcane.wormholes.util.Direction;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static net.minecraft.core.Direction.NORTH;
import static net.minecraft.core.Direction.SOUTH;
import static net.minecraft.core.Direction.EAST;
import static net.minecraft.core.Direction.WEST;
import static net.minecraft.core.Direction.UP;
import static net.minecraft.core.Direction.DOWN;

public final class MinecraftDoorService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftDoorService> SERVICES = new ConcurrentHashMap<>();
    private static final long TRANSIT_COOLDOWN_MILLIS = 1_000L;

    private final WormholesModRuntime runtime;
    private final MinecraftDoorAccessMenu accessMenu;
    private Options options;
    private ExecutorService storage;
    private MinecraftServer server;
    private volatile WormholesModConfiguration configuration;
    private long generation;
    private final Map<UUID, ActiveDoor> doors = new LinkedHashMap<>();
    private final Set<UUID> pendingItems = new HashSet<>();
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<UUID, Set<Entity>> preparedArrivalBodies = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Position> positions = new HashMap<>();
    private final Map<UUID, DeferredCrossing> deferredCrossings = new HashMap<>();
    private final DoorAutoCloseBook autoClose = new DoorAutoCloseBook();
    private DoorStateService state;
    private MinecraftPocketRooms pockets;
    private MinecraftPocketRules rules;
    private MinecraftPocketService pocketOperations;
    private MinecraftDoorPresentation presentation;
    private final Map<UUID, PocketTrip> pocketTrips = new HashMap<>();
    private final Map<UUID, CompletableFuture<PocketSpace>> pocketPreparations = new HashMap<>();
    private final Set<PocketBinding> pocketPreviews = new HashSet<>();
    private final Map<PocketBinding, Long> pocketPreviewRetries = new HashMap<>();
    private final Map<UUID, EndpointProjection> endpointProjections = new HashMap<>();
    private final Map<UUID, Long> endpointProjectionRetries = new HashMap<>();
    private final Map<Long, PocketSpace> pocketChunks = new HashMap<>();
    private volatile boolean closed;

    public MinecraftDoorService(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
        accessMenu = new MinecraftDoorAccessMenu(runtime, this);
    }

    public void load(Options options) throws IOException {
        runtime.requireServerThread();
        if (server != null) {
            throw new IllegalStateException("Door service is already loaded");
        }
        this.options = Objects.requireNonNull(options);
        server = runtime.server();
        configuration = runtime.configuration();
        state = DoorStateService.under(options.directory(), MinecraftJsonDocuments.INSTANCE);
        storage = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("Wormholes-door-storage").factory());
        pockets = new MinecraftPocketRooms(runtime);
        rules = new MinecraftPocketRules(runtime, this);
        pocketOperations = new MinecraftPocketService(runtime,
            new MinecraftPocketService.Options(options.directory(), this, pockets, storage));
        presentation = new MinecraftDoorPresentation(runtime, this);
        closed = false;
        generation++;
        SERVICES.put(server, this);
        for (PocketSpace space : state.spaces()) {
            index(space);
        }
        for (PlacedDoorEndpoint endpoint : state.endpoints()) {
            doors.put(endpoint.identity().itemId(), new ActiveDoor(endpoint));
        }
    }

    public static MinecraftDoorService forServer(MinecraftServer server) {
        MinecraftDoorService service = SERVICES.get(server);
        return service != null && service.enabled() ? service : null;
    }

    public boolean enabled() {
        WormholesModConfiguration current = configuration;
        return !closed && current != null && current.settings().getMain().dimensionalDoorsEnabled;
    }

    public boolean projectionEnabled() {
        return enabled() && configuration.settings().getDoors().projectionEnabled;
    }

    public MinecraftPocketRules rules() {
        return Objects.requireNonNull(rules, "Pocket rules are not loaded");
    }

    boolean changingPocket(BlockPos position) {
        return pocketOperations.protects(position);
    }

    boolean travelling(UUID entityId) {
        return flights.containsKey(entityId) || pocketTrips.containsKey(entityId) || rules.rescuing(entityId);
    }

    void recordTeleport(Entity entity) {
        deferredCrossings.remove(entity.getUUID());
        if (entity instanceof ServerPlayer player) {
            positions.put(entity.getUUID(), new Position(player.level(), player.position()));
        }
    }

    void cancelDeparture(Entity entity) {
        Flight flight = flights.remove(entity.getUUID());
        if (flight != null) {
            flight.lease().close();
        }
        PocketTrip trip = pocketTrips.get(entity.getUUID());
        if (trip != null) {
            finishPocket(entity, trip, false);
        } else if (flight != null) {
            DoorTransitGate.complete(flight.source().cycle, flight.transit(), false, flight.source().cycle.portalActive());
        }
    }

    public boolean canPrepare(ServerPlayer player, UUID sourceId, UUID destinationId) {
        runtime.requireServerThread();
        if (!enabled() || !player.isAlive() || player.hasDisconnected()) {
            return false;
        }
        ActiveDoor source = doors.get(sourceId);
        Snapshot snapshot = source == null ? null : capture(source.endpoint);
        if (snapshot == null || !snapshot.active() || snapshot.level() != player.level() || !canEnter(player, source.endpoint)) {
            return false;
        }
        if (source.endpoint.identity().kind() == DoorKind.PAIR) {
            PlacedDoorEndpoint mate = state.findMate(source.endpoint.identity()).orElse(null);
            return mate != null && mate.identity().itemId().equals(destinationId) && canAccess(player, mate);
        }
        return projectionDestination(new DoorView(snapshot.endpoint(), snapshot.level(), snapshot.plane(), snapshot.active()), player.getUUID())
            .filter(destination -> destination.id().equals(destinationId)).isPresent();
    }

    public boolean canCraft(ServerPlayer player) {
        return enabled() && (options.access().administrator(player) || options.access().permission(player, DoorAccessPolicy.CRAFT_NODE));
    }

    public boolean resetAllowed() {
        runtime.requireServerThread();
        if (!flights.isEmpty() || !pocketTrips.isEmpty() || !pocketPreviews.isEmpty() || !pocketPreparations.isEmpty()) {
            return false;
        }
        for (PocketSpace space : state.spaces()) {
            if (pocketOperations.busy(space.spaceId())) {
                return false;
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isPocketLevel(player.level())) {
                return false;
            }
        }
        return true;
    }

    public DoorStateService state() {
        return Objects.requireNonNull(state, "Door state is not loaded");
    }

    public MinecraftPocketService pocketOperations() {
        return Objects.requireNonNull(pocketOperations, "Pocket operations are not loaded");
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        MinecraftPocketService.registerCommands(dispatcher, this::pocketOperations);
        dispatcher.register(Commands.literal("wormholes")
            .then(Commands.literal("door").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("pair").executes(context -> givePair(context.getSource().getPlayerOrException(), DoorForm.DOOR)))
                .then(Commands.literal("trapdoor-pair").executes(context -> givePair(context.getSource().getPlayerOrException(), DoorForm.TRAPDOOR)))
                .then(Commands.literal("personal").executes(context -> givePocket(context.getSource().getPlayerOrException(), DoorKind.PERSONAL, DoorForm.DOOR)))
                .then(Commands.literal("public").executes(context -> givePocket(context.getSource().getPlayerOrException(), DoorKind.PUBLIC, DoorForm.DOOR)))
                .then(Commands.literal("trapdoor-personal").executes(context -> givePocket(context.getSource().getPlayerOrException(), DoorKind.PERSONAL, DoorForm.TRAPDOOR)))
                .then(Commands.literal("trapdoor-public").executes(context -> givePocket(context.getSource().getPlayerOrException(), DoorKind.PUBLIC, DoorForm.TRAPDOOR)))));
    }

    public int givePair(ServerPlayer player, DoorForm form) {
        runtime.requireServerThread();
        if (!enabled()) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.COMMAND_DOORS_UNAVAILABLE, Map.of()));
            return 0;
        }
        give(player, MinecraftDoorItems.pairKit(form));
        return 1;
    }

    public int givePocket(ServerPlayer player, DoorKind kind, DoorForm form) {
        runtime.requireServerThread();
        if (!enabled()) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.COMMAND_DOORS_UNAVAILABLE, Map.of()));
            return 0;
        }
        DoorItemIdentity identity = switch (kind) {
            case PERSONAL -> DoorItemIdentity.newPersonal(form);
            case PUBLIC -> DoorItemIdentity.newPublic(form);
            default -> throw new IllegalArgumentException("A pocket door must be personal or public");
        };
        give(player, MinecraftDoorItems.door(identity));
        return 1;
    }

    public boolean useItem(ServerPlayer player, InteractionHand hand) {
        runtime.requireServerThread();
        if (!enabled()) {
            return false;
        }
        ItemStack held = player.getItemInHand(hand);
        Optional<MinecraftDoorItems.PairKit> decoded = MinecraftDoorItems.kit(held);
        if (decoded.isEmpty()) {
            return false;
        }
        MinecraftDoorItems.PairKit kit = decoded.get();
        if (!pendingItems.add(kit.kitId())) {
            return true;
        }
        DoorPairIdentity pair = DoorPairIdentity.forKit(kit.kitId());
        mutate(player, activeState -> activeState.registerPair(pair), registered -> {
            pendingItems.remove(kit.kitId());
            if (!MinecraftDoorItems.kit(player.getItemInHand(hand)).filter(kit::equals).isPresent()) {
                return;
            }
            player.getItemInHand(hand).shrink(1);
            for (PairEndpoint side : PairEndpoint.values()) {
                give(player, MinecraftDoorItems.door(DoorItemIdentity.paired(pair.itemId(side), pair.pairId(), side, kit.form())));
            }
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_PAIR_UNPACKED, Map.of()));
        }, () -> pendingItems.remove(kit.kitId()));
        return true;
    }

    public boolean useBlock(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        runtime.requireServerThread();
        if (!enabled()) {
            return false;
        }
        if (useItem(player, hand)) {
            return true;
        }
        ItemStack held = player.getItemInHand(hand);
        BlockPos clickedBlock = lower(player.level(), hit.getBlockPos());
        PlacedDoorEndpoint clicked = state.findEndpoint(worldId(player.level()), clickedBlock.getX(),
            clickedBlock.getY(), clickedBlock.getZ()).orElse(null);
        if (clicked != null && hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && held.isEmpty()
            && canManage(player, clicked.identity().itemId())) {
            accessMenu.open(player, clicked);
            return true;
        }
        if (clicked != null && !canAccess(player, clicked)) {
            Snapshot denied = capture(clicked);
            presentation.deny(player, clicked, denied == null ? null : denied.plane());
            return true;
        }
        if (held.getItem() instanceof BlockItem && !mayBuild(player, new BlockPlaceContext(player, hand, held, hit).getClickedPos())) {
            return true;
        }
        Optional<DoorItemIdentity> decoded = MinecraftDoorItems.identity(held);
        if (decoded.isEmpty()) {
            return false;
        }
        DoorItemIdentity identity = decoded.get();
        if (identity.kind() == DoorKind.RETURN || !options.access().permission(player, DoorAccessPolicy.PLACE_NODE)
            || !(held.getItem() instanceof BlockItem blockItem)
            || !supports(blockItem.getBlock().defaultBlockState(), identity.form())) {
            return true;
        }
        if (identity.kind() == DoorKind.PAIR && state.findPair(identity.pairId()).isEmpty()) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_PAIR_MISSING, Map.of()));
            return true;
        }
        BlockPlaceContext placement = new BlockPlaceContext(player, hand, held, hit);
        if (!placement.canPlace() || !pendingItems.add(identity.itemId())) {
            return true;
        }
        ServerLevel level = player.level();
        BlockPos block = placement.getClickedPos().immutable();
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(position(level, block), identity);
        if (identity.kind() == DoorKind.PAIR && isPocketLevel(level)) {
            growRoom(player, hand, hit, blockItem, endpoint);
            return true;
        }
        boolean existingAccess = state.accessRecord(identity.itemId()).isPresent();
        mutate(player, activeState -> activeState.registerEndpoint(endpoint, player.getUUID()), registered -> {
            boolean placed = false;
            try {
                if (player.level() == level && !player.hasDisconnected()
                    && MinecraftDoorItems.identity(player.getItemInHand(hand)).filter(identity::equals).isPresent()
                    && new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit).canPlace()) {
                    placed = completeDoorPlacement(player, hand,
                        blockItem.useOn(new UseOnContext(player, hand, hit)).consumesAction() && capture(endpoint) != null);
                }
                if (placed) {
                    doors.put(identity.itemId(), new ActiveDoor(endpoint));
                } else {
                    mutate(player, activeState -> {
                        activeState.removeEndpoint(endpoint.position());
                        if (!existingAccess) {
                            activeState.removeAccessRecord(identity.itemId());
                        }
                        return true;
                    }, ignored -> { }, () -> { });
                }
            } finally {
                pendingItems.remove(identity.itemId());
            }
        }, () -> pendingItems.remove(identity.itemId()));
        return true;
    }

    static boolean completeDoorPlacement(ServerPlayer player, InteractionHand hand, boolean placed) {
        if (!placed) {
            return false;
        }
        ItemStack held = player.getItemInHand(hand);
        if (player.hasInfiniteMaterials() && MinecraftDoorItems.identity(held).isPresent()) {
            held.shrink(1);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastFullState();
        return true;
    }

    private void growRoom(ServerPlayer player, InteractionHand hand, BlockHitResult hit, BlockItem item, PlacedDoorEndpoint endpoint) {
        ServerLevel level = player.level();
        BlockPos block = new BlockPos(endpoint.position().x(), endpoint.position().y(), endpoint.position().z());
        BlockState predicted = item.getBlock().getStateForPlacement(new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit));
        if (predicted == null || endpoint.identity().form() != DoorForm.DOOR) {
            pendingItems.remove(endpoint.identity().itemId());
            return;
        }
        DoorwayPlane plane = new DoorwayPlane(block.getX(), block.getY(), block.getZ(),
            direction(predicted.getValue(BlockStateProperties.HORIZONTAL_FACING)));
        BlockState beforeLower = level.getBlockState(block);
        BlockState beforeUpper = level.getBlockState(block.above());
        ItemStack refund = player.getItemInHand(hand).copyWithCount(1);
        boolean[] placed = {false};
        pocketOperations.grow(player, endpoint, plane, () -> {
            if (closed || player.hasDisconnected() || player.level() != level
                || !MinecraftDoorItems.identity(player.getItemInHand(hand)).filter(endpoint.identity()::equals).isPresent()
                || !new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit).canPlace()) {
                return false;
            }
            placed[0] = completeDoorPlacement(player, hand,
                item.useOn(new UseOnContext(player, hand, hit)).consumesAction() && capture(endpoint) != null);
            return placed[0];
        }).whenCompleteAsync((updated, failure) -> {
            pendingItems.remove(endpoint.identity().itemId());
            if (failure != null) {
                LOGGER.error("Could not grow a pocket room through door {}", endpoint.identity().itemId(), failure);
                if (placed[0]) {
                    level.setBlock(block, beforeLower, Block.UPDATE_CLIENTS);
                    level.setBlock(block.above(), beforeUpper, Block.UPDATE_CLIENTS);
                    if (!refund.isEmpty()) {
                        give(player, refund);
                    }
                }
            }
        }, server);
    }

    void publishEndpoint(PlacedDoorEndpoint endpoint) {
        runtime.requireServerThread();
        doors.put(endpoint.identity().itemId(), new ActiveDoor(endpoint));
    }

    static net.minecraft.core.Direction facing(Direction direction) {
        return switch (direction) {
            case N -> NORTH;
            case S -> SOUTH;
            case E -> EAST;
            case W -> WEST;
            case U -> UP;
            case D -> DOWN;
        };
    }

    private static Direction direction(net.minecraft.core.Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.N;
            case SOUTH -> Direction.S;
            case EAST -> Direction.E;
            case WEST -> Direction.W;
            case UP -> Direction.U;
            case DOWN -> Direction.D;
        };
    }

    public boolean beforeBreak(ServerPlayer player, BlockPos clicked) {
        runtime.requireServerThread();
        if (!enabled()) {
            return false;
        }
        ServerLevel level = player.level();
        BlockPos block = lower(level, clicked);
        if (!mayBuild(player, block)) {
            return true;
        }
        PlacedDoorEndpoint endpoint = state.findEndpoint(worldId(level), block.getX(), block.getY(), block.getZ()).orElse(null);
        if (endpoint == null) {
            return false;
        }
        if (endpoint.identity().kind() == DoorKind.RETURN
            || !DoorAccessPolicy.canManage(state.accessRecord(endpoint.identity().itemId()).orElse(null), player.getUUID(), options.access().administrator(player))) {
            return true;
        }
        UUID id = endpoint.identity().itemId();
        if (!pendingItems.add(id)) {
            return true;
        }
        BlockState expected = level.getBlockState(block);
        Item material = expected.getBlock().asItem();
        mutate(player, activeState -> activeState.removeEndpoint(endpoint.position()), removed -> {
            pendingItems.remove(id);
            doors.remove(id);
            autoClose.forget(id);
            if (!removed.isEmpty() && level.getBlockState(block).is(expected.getBlock())) {
                level.destroyBlock(block, false, player);
                level.addFreshEntity(new ItemEntity(level, block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D,
                    MinecraftDoorItems.door(endpoint.identity(), material)));
            }
        }, () -> pendingItems.remove(id));
        return true;
    }

    public void tick() {
        runtime.requireServerThread();
        long now = System.currentTimeMillis();
        expireEndpointProjections(now);
        if (!enabled()) {
            presentation.clear();
            return;
        }
        presentation.tick();
        rules.tick();
        pocketOperations.tick();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        Set<UUID> visited = new HashSet<>();
        retryCrossings(visited);
        for (ActiveDoor door : List.copyOf(doors.values())) {
            if (pendingItems.contains(door.endpoint.identity().itemId())) {
                continue;
            }
            Snapshot snapshot = capture(door.endpoint);
            if (snapshot == null) {
                removeStaleEndpoint(door);
                continue;
            }
            door.cycle.observe(snapshot.active());
            autoClose.observe(door.endpoint.identity().itemId(), snapshot.active());
            if (!snapshot.active()) {
                continue;
            }
            DoorwayPlane plane = snapshot.plane();
            AABB box = new AABB(plane.center().x() - 4.5D, plane.center().y() - 3.0D, plane.center().z() - 4.5D,
                plane.center().x() + 4.5D, plane.center().y() + 3.0D, plane.center().z() + 4.5D);
            for (Entity entity : snapshot.level().getEntities((Entity) null, box, Entity::isAlive)) {
                if (visited.contains(entity.getUUID()) || travelling(entity.getUUID()) || runtime.portals().travelling(entity.getUUID()) || cooldowns.containsKey(entity.getUUID())
                    || !canEnter(entity, door.endpoint)) {
                    continue;
                }
                Position previous = positions.get(entity.getUUID());
                Vec3 start = previous != null && previous.level() == snapshot.level()
                    ? previous.point() : new Vec3(entity.xo, entity.yo, entity.zo);
                Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(plane, vector(start), vector(entity.position()),
                    entity.getBbWidth() * 0.5D, entity.getBbHeight());
                if (crossing.isEmpty()) {
                    continue;
                }
                visited.add(entity.getUUID());
                DoorTravelerClass travelerClass = entity instanceof LivingEntity || entity instanceof VehicleEntity ? DoorTravelerClass.LIVING : DoorTravelerClass.OBJECT;
                DoorTransit transit = new DoorTransit(plane, crossing.get(), entity.getYRot(), entity.getXRot(),
                    entity.getBbWidth() * 0.5D, entity.getBbHeight(), travelerClass,
                    travelerClass == DoorTravelerClass.OBJECT ? vector(entity.getDeltaMovement()) : null);
                if (entity instanceof ServerPlayer player && runtime.clientViews().deferTravel(player.getUUID(), door.endpoint.identity().itemId())) {
                    deferredCrossings.putIfAbsent(player.getUUID(), new DeferredCrossing(player, door, snapshot.level(), transit));
                    continue;
                }
                depart(entity, door, snapshot, transit);
            }
        }
        positions.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            positions.put(player.getUUID(), new Position(player.level(), player.position()));
        }
    }

    public boolean crossPrepared(ServerPlayer player, UUID source, PortalCrossing crossing) {
        runtime.requireServerThread();
        if (!enabled() || !player.isAlive() || player.isPassenger() || player.isVehicle()
            || travelling(player.getUUID()) || runtime.portals().travelling(player.getUUID()) || cooldowns.containsKey(player.getUUID())) {
            return false;
        }
        ActiveDoor door = doors.get(source);
        Snapshot snapshot = door == null ? null : capture(door.endpoint);
        if (snapshot == null || !snapshot.active() || snapshot.level() != player.level() || !canEnter(player, door.endpoint)) {
            return false;
        }
        Optional<DoorwayCrossing> entry = DoorTransitGate.prepared(snapshot.plane(), crossing, player.getBbWidth() * 0.5D, player.getBbHeight());
        if (entry.isEmpty()) {
            return false;
        }
        deferredCrossings.remove(player.getUUID());
        door.cycle.observe(snapshot.active());
        DoorTransit transit = new DoorTransit(snapshot.plane(), entry.get(), player.getYRot(), player.getXRot(),
            player.getBbWidth() * 0.5D, player.getBbHeight(), DoorTravelerClass.LIVING,
            new DoorVec3(crossing.velocity().x(), crossing.velocity().y(), crossing.velocity().z()), crossing);
        depart(player, door, snapshot, transit);
        return travelling(player.getUUID());
    }

    private void retryCrossings(Set<UUID> visited) {
        Iterator<Map.Entry<UUID, DeferredCrossing>> iterator = deferredCrossings.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, DeferredCrossing> entry = iterator.next();
            DeferredCrossing pending = entry.getValue();
            ServerPlayer player = pending.player();
            visited.add(entry.getKey());
            Snapshot snapshot = capture(pending.source().endpoint);
            if (!player.isAlive() || player.level() != pending.level() || doors.get(pending.source().endpoint.identity().itemId()) != pending.source()
                || snapshot == null || !snapshot.active() || !snapshot.plane().equals(pending.transit().sourcePlane())
                || travelling(player.getUUID()) || runtime.portals().travelling(player.getUUID()) || cooldowns.containsKey(player.getUUID()) || !canEnter(player, pending.source().endpoint)) {
                iterator.remove();
                continue;
            }
            if (runtime.clientViews().deferTravel(player.getUUID(), pending.source().endpoint.identity().itemId())) {
                continue;
            }
            iterator.remove();
            depart(player, pending.source(), snapshot, pending.transit());
        }
    }

    private void removeStaleEndpoint(ActiveDoor door) {
        PlacedDoorEndpoint endpoint = door.endpoint;
        ServerLevel level = level(endpoint.position().worldKey());
        if (level == null || !level.hasChunk(endpoint.position().x() >> 4, endpoint.position().z() >> 4)) {
            return;
        }
        BlockPos block = new BlockPos(endpoint.position().x(), endpoint.position().y(), endpoint.position().z());
        PocketSpace space = spaceAt(level, block);
        if (space != null && pocketOperations.busy(space.spaceId()) || !pendingItems.add(endpoint.identity().itemId())) {
            return;
        }
        long startedGeneration = generation;
        DoorStateService store = state;
        CompletableFuture.supplyAsync(() -> {
            try {
                if (store.findEndpointByItem(endpoint.identity().itemId()).filter(endpoint::equals).isEmpty()) {
                    return false;
                }
                return store.removeEndpoint(endpoint.position()).isPresent();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, storage).whenCompleteAsync((removed, failure) -> {
            if (closed || generation != startedGeneration) {
                if (failure != null) {
                    LOGGER.error("Could not remove stale dimensional-door endpoint {} during shutdown", endpoint.identity().itemId(), failure);
                }
                return;
            }
            pendingItems.remove(endpoint.identity().itemId());
            if (failure != null) {
                LOGGER.error("Could not remove stale dimensional-door endpoint {}", endpoint.identity().itemId(), failure);
                return;
            }
            if (Boolean.TRUE.equals(removed) && doors.remove(endpoint.identity().itemId(), door)) {
                autoClose.forget(endpoint.identity().itemId());
                preparedArrivalBodies.remove(endpoint.identity().itemId());
            }
        }, server);
    }

    public void playerDisconnected(ServerPlayer player) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        endpointProjections.values().removeIf(preparation -> {
            preparation.observers.remove(player.getUUID());
            if (!preparation.observers.isEmpty()) {
                return false;
            }
            preparation.lease.close();
            return true;
        });
        rules.playerDisconnected(player);
        presentation.disconnected(player);
        Flight flight = flights.remove(player.getUUID());
        if (flight != null) {
            flight.lease.close();
        }
        pocketTrips.remove(player.getUUID());
        for (Set<Entity> travelers : preparedArrivalBodies.values()) {
            travelers.removeIf(traveler -> traveler.getUUID().equals(player.getUUID()));
        }
        preparedArrivalBodies.values().removeIf(Set::isEmpty);
        positions.remove(player.getUUID());
        deferredCrossings.remove(player.getUUID());
        cooldowns.remove(player.getUUID());
    }

    @Override
    public void close() {
        if (server == null) {
            return;
        }
        runtime.requireServerThread();
        closed = true;
        SERVICES.remove(server, this);
        generation++;
        for (Flight flight : flights.values()) {
            flight.lease.close();
        }
        flights.clear();
        deferredCrossings.clear();
        pocketTrips.clear();
        pocketPreviews.clear();
        pocketPreviewRetries.clear();
        for (EndpointProjection preparation : endpointProjections.values()) {
            preparation.lease.close();
        }
        endpointProjections.clear();
        endpointProjectionRetries.clear();
        for (CompletableFuture<PocketSpace> preparation : List.copyOf(pocketPreparations.values())) {
            preparation.completeExceptionally(new IllegalStateException("Pocket preparation was stopped"));
        }
        pocketPreparations.clear();
        pocketChunks.clear();
        if (presentation != null) {
            presentation.close();
            presentation = null;
        }
        if (rules != null) {
            rules.close();
            rules = null;
        }
        if (pocketOperations != null) {
            pocketOperations.close();
            pocketOperations = null;
        }
        if (pockets != null) {
            pockets.close();
            pockets = null;
        }
        doors.clear();
        positions.clear();
        cooldowns.clear();
        pendingItems.clear();
        autoClose.clear();
        preparedArrivalBodies.clear();
        ExecutorService pendingStorage = storage;
        storage = null;
        try {
            if (pendingStorage != null) {
                pendingStorage.shutdown();
            }
            if (pendingStorage != null && !pendingStorage.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.error("Wormholes door persistence did not finish before shutdown");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while waiting for Wormholes door persistence", exception);
        } finally {
            state = null;
            server = null;
            options = null;
        }
    }

    private void depart(Entity entity, ActiveDoor source, Snapshot sourceSnapshot, DoorTransit transit) {
        DoorKind kind = source.endpoint.identity().kind();
        if (kind == DoorKind.PERSONAL || kind == DoorKind.PUBLIC) {
            enterPocket(entity, source, sourceSnapshot, transit);
            return;
        }
        ReturnTicket returnTicket = kind == DoorKind.RETURN ? state.getReturnTicket(entity.getUUID()).orElse(null) : null;
        PlacedDoorEndpoint destination = kind == DoorKind.PAIR ? state.findMate(source.endpoint.identity()).orElse(null)
            : returnTicket == null ? null : state.findEndpointByItem(returnTicket.sourceEndpointId()).orElse(null);
        if (kind == DoorKind.RETURN && (destination == null || destination.identity().kind() == DoorKind.RETURN
            || level(destination.position().worldKey()) == null || isPocketLevel(level(destination.position().worldKey())))) {
            returnFallback(entity, source, sourceSnapshot, transit, returnTicket);
            return;
        }
        if (destination == null || !canAccess(entity, destination)) {
            return;
        }
        ServerLevel level = level(destination.position().worldKey());
        if (level == null || transit.claimsOpenCycle() && !source.cycle.tryBegin(sourceSnapshot.active())) {
            return;
        }
        DoorPosition block = destination.position();
        ChunkLease lease;
        try {
            lease = runtime.leases().retain(level, worldId(level), block.x() >> 4, block.z() >> 4);
        } catch (RuntimeException exception) {
            DoorTransitGate.complete(source.cycle, transit, false, sourceSnapshot.active());
            LOGGER.error("Could not retain dimensional-door destination {}", destination.identity().itemId(), exception);
            return;
        }
        Flight flight = new Flight(lease, entity.level(), entity.position(), System.currentTimeMillis() + 30_000L, source, transit);
        flights.put(entity.getUUID(), flight);
        ClientViewMessage.TravelBegin prepared = preparedCrossing(entity);
        long startedGeneration = generation;
        lease.ready().whenCompleteAsync((ready, error) -> {
            if (closed || generation != startedGeneration) {
                if (flights.remove(entity.getUUID(), flight)) {
                    lease.close();
                }
                return;
            }
            boolean success = false;
            try {
                if (error != null) {
                    LOGGER.error("Could not prepare dimensional-door destination {}", destination.identity().itemId(), error);
                }
                Snapshot current = capture(source.endpoint);
                Snapshot target = capture(destination);
                if (!enabled() || !Boolean.TRUE.equals(ready) || !entity.isAlive() || current == null || !current.active() || target == null
                    || !current.plane().equals(transit.sourcePlane()) || entity.level() != flight.level
                    || flights.get(entity.getUUID()) != flight || entity.position().distanceToSqr(flight.point) > 1.0D
                    || System.currentTimeMillis() >= flight.expiresAt || !canEnter(entity, source.endpoint) || !canAccess(entity, destination)
                    || prepared != null && !runtime.clientViews().crossing(entity.getUUID(), prepared)
                    || state.findEndpointByItem(source.endpoint.identity().itemId()).filter(source.endpoint::equals).isEmpty()
                    || state.findEndpointByItem(destination.identity().itemId()).filter(destination::equals).isEmpty()) {
                    return;
                }
                success = arrive(entity, source, transit, target);
                if (success && returnTicket != null) {
                    removeTicket(entity, returnTicket);
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Dimensional-door traversal failed for {}", entity.getUUID(), exception);
            } finally {
                boolean owned = finishFlight(entity, flight, success);
                if (owned && !success && entity instanceof ServerPlayer player && prepared != null) {
                    runtime.clientViews().cancelPreparation(player, prepared);
                }
            }
        }, server);
    }

    private void enterPocket(Entity entity, ActiveDoor source, Snapshot snapshot, DoorTransit transit) {
        ReturnTicket ticket = null;
        if (transit.travelerClass() != DoorTravelerClass.OBJECT) {
            int side = transit.direction().entrySideSign();
            Optional<DoorVec3> point = DoorArrivals.findSafeVerticalDoorStanding(
                DoorArrivals.arrivalPoint(snapshot.plane(), transit, side), DoorPlanePairing.arrivalYOffsets(snapshot.plane(), side),
                candidate -> safe(entity, snapshot.level(), candidate, !snapshot.plane().horizontal()));
            if (point.isEmpty()) {
                return;
            }
            DoorVec3 returnPoint = point.get();
            DoorArrivals.Facing facing = DoorArrivals.arrivalFacing(snapshot.plane(), transit, side);
            ticket = new ReturnTicket(entity.getUUID(), source.endpoint.identity().itemId(), worldId(snapshot.level()),
                snapshot.level().dimension().identifier().toString(), returnPoint.x(), returnPoint.y(), returnPoint.z(),
                facing.yaw(), facing.pitch());
        }
        if (transit.claimsOpenCycle() && !source.cycle.tryBegin(snapshot.active())) {
            return;
        }
        PocketTrip trip = new PocketTrip(source, transit, entity.level(), entity.position(), generation,
            System.currentTimeMillis() + 30_000L, ticket, preparedCrossing(entity));
        pocketTrips.put(entity.getUUID(), trip);
        MainConfig config = runtime.configuration().settings().getMain();
        PocketShell shell = new PocketShell(config.pocketRoomSize, config.pocketShellMaterial, config.pocketReturnDoorMaterial);
        PocketsConfig pocketConfig = runtime.configuration().settings().getPockets();
        PocketRules defaults = new PocketRules(pocketConfig.rulesDefaultMobs, pocketConfig.rulesDefaultPvp,
            pocketConfig.rulesDefaultKeepInventory, pocketConfig.rulesDefaultFixedTime, PocketRules.BuildPolicy.parse(pocketConfig.rulesDefaultBuild));
        UUID traveler = entity.getUUID();
        PocketSpace standingIn = spaceAt(snapshot.level(), snapshot.block());
        boolean insidePocketWorld = isPocketLevel(snapshot.level());
        mutate(entity, activeState -> {
            PocketDoorDestination destination = pocketOperations.destination(source.endpoint.identity(), traveler);
            PocketSpace existing = activeState.findPocket(destination.binding()).orElse(null);
            if (!PocketRooms.allowsPocketEntry(insidePocketWorld, standingIn, existing)) {
                return null;
            }
            PocketSpace space = existing == null
                ? activeState.replacePocket(activeState.getOrAllocatePocket(destination.binding(), shell).withRules(defaults)) : existing;
            if (destination.isInstanced() && space.instance() == null) {
                return activeState.replacePocket(space.withTemplateName(destination.instancedTemplate()).withInstance(
                    PocketInstances.newInstance(destination.instancedTemplate(), traveler, pocketConfig.instanceReset, System.currentTimeMillis())));
            }
            return space.instance() == null ? space : activeState.replacePocket(space.withInstance(
                space.instance().withLastOccupied(System.currentTimeMillis())));
        }, space -> {
            if (space == null || !valid(entity, trip) || pocketOperations.busy(space.spaceId())) {
                finishPocket(entity, trip, false);
                return;
            }
            index(space);
            preparePocket(space).thenComposeAsync(prepared -> {
                if (!valid(entity, trip)) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Pocket traversal expired"));
                }
                return pockets.load(prepared);
            }, server).whenCompleteAsync((room, error) -> {
                if (error != null) {
                    LOGGER.error("Could not provision pocket {}", space.spaceId(), error);
                    finishPocket(entity, trip, false);
                    return;
                }
                if (!valid(entity, trip)) {
                    room.close();
                    finishPocket(entity, trip, false);
                    return;
                }
                persistPocketEntry(entity, trip, room);
            }, server);
        }, () -> finishPocket(entity, trip, false));
    }

    private CompletableFuture<PocketSpace> preparePocket(PocketSpace space) {
        runtime.requireServerThread();
        CompletableFuture<PocketSpace> pending = pocketPreparations.get(space.spaceId());
        if (pending != null) {
            return pending;
        }
        if (!enabled() || pocketOperations.busy(space.spaceId())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pocket is unavailable"));
        }
        long startedGeneration = generation;
        MinecraftServer owner = server;
        DoorStateService persistentState = state;
        ExecutorService persistence = storage;
        CompletableFuture<PocketSpace> result = new CompletableFuture<>();
        pocketPreparations.put(space.spaceId(), result);
        boolean provision = persistentState.findEndpointByItem(new PocketLayout(space).returnDoorIdentity().itemId()).isEmpty();
        CompletableFuture<MinecraftPocketRooms.Prepared> loading;
        try {
            loading = provision ? pockets.prepare(space) : pockets.load(space);
        } catch (RuntimeException failure) {
            pocketPreparations.remove(space.spaceId(), result);
            result.completeExceptionally(failure);
            return result;
        }
        loading.whenCompleteAsync((room, loadError) -> {
            if (loadError != null) {
                finishPreparation(space, result, null, loadError, startedGeneration);
                return;
            }
            if (closed || generation != startedGeneration) {
                room.close();
                result.completeExceptionally(new IllegalStateException("Pocket preparation was stopped"));
                return;
            }
            CompletableFuture<PocketSpace> furnished;
            try {
                furnished = pocketOperations.furnish(space);
            } catch (RuntimeException failure) {
                room.close();
                finishPreparation(space, result, null, failure, startedGeneration);
                return;
            }
            furnished.thenApplyAsync(prepared -> {
                try {
                    persistentState.registerEndpoint(room.endpoint());
                    return prepared;
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }, persistence).whenCompleteAsync((prepared, error) -> {
                try {
                    if (!closed && generation == startedGeneration && error == null) {
                        doors.put(room.endpoint().identity().itemId(), new ActiveDoor(room.endpoint()));
                        index(prepared);
                    }
                    finishPreparation(space, result, prepared, error, startedGeneration);
                } finally {
                    room.close();
                }
            }, owner);
        }, owner);
        return result;
    }

    private void finishPreparation(PocketSpace space, CompletableFuture<PocketSpace> result, PocketSpace prepared,
                                   Throwable failure, long startedGeneration) {
        if (closed || generation != startedGeneration) {
            result.completeExceptionally(new IllegalStateException("Pocket preparation was stopped", failure));
            return;
        }
        pocketPreparations.remove(space.spaceId(), result);
        if (failure == null) {
            result.complete(prepared);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private void persistPocketEntry(Entity entity, PocketTrip trip, MinecraftPocketRooms.Prepared room) {
        PlacedDoorEndpoint endpoint = room.endpoint();
        mutate(entity, activeState -> {
            if (trip.ticket() != null) {
                activeState.putReturnTicket(trip.ticket());
            }
            return true;
        }, ignored -> {
            boolean success = false;
            try {
                doors.put(endpoint.identity().itemId(), new ActiveDoor(endpoint));
                if (valid(entity, trip)) {
                    PocketEntryCoordinates entry = room.layout().entry();
                    DoorVec3 point = new DoorVec3(entry.x(), entry.y(), entry.z());
                    if (safe(entity, room.level(), point, trip.transit().travelerClass() == DoorTravelerClass.LIVING)) {
                        DoorwayPlane pocketPlane = room.layout().returnDoorPlane();
                        DoorArrivals.Facing facing = DoorArrivals.arrivalFacing(pocketPlane, trip.transit(), -1);
                        success = transitTeleport(entity, trip.source().endpoint, endpoint.identity().itemId(), room.level(), point, facing.yaw(), facing.pitch(),
                            trip.transit().carriesMomentum() ? DoorVelocityTransform.mapToSide(pocketPlane, trip.transit(), trip.transit().velocity(), -1) : new DoorVec3(0, 0, 0));
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Pocket entry failed for {}", entity.getUUID(), exception);
            } finally {
                if (!success && trip.ticket() != null) {
                    removeTicket(entity, trip.ticket());
                }
                room.close();
                finishPocket(entity, trip, success);
            }
        }, () -> {
            room.close();
            finishPocket(entity, trip, false);
        });
    }

    private void returnFallback(Entity entity, ActiveDoor source, Snapshot snapshot, DoorTransit transit, ReturnTicket ticket) {
        if (ticket == null || !(entity instanceof ServerPlayer)) {
            return;
        }
        ServerLevel destination = level(ticket.sourceWorldKey());
        if (destination == null || transit.claimsOpenCycle() && !source.cycle.tryBegin(snapshot.active())) {
            return;
        }
        PocketTrip trip = new PocketTrip(source, transit, entity.level(), entity.position(), generation,
            System.currentTimeMillis() + 30_000L, ticket, preparedCrossing(entity));
        pocketTrips.put(entity.getUUID(), trip);
        ChunkLease lease;
        try {
            lease = runtime.leases().retain(destination, worldId(destination), (int) Math.floor(ticket.x()) >> 4, (int) Math.floor(ticket.z()) >> 4);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not prepare pocket return for {}", entity.getUUID(), exception);
            finishPocket(entity, trip, false);
            return;
        }
        Flight flight = new Flight(lease, entity.level(), entity.position(), trip.deadline(), source, transit);
        flights.put(entity.getUUID(), flight);
        lease.ready().whenCompleteAsync((ready, error) -> {
            boolean success = false;
            try {
                if (error != null) {
                    LOGGER.error("Could not load pocket return for {}", entity.getUUID(), error);
                }
                if (Boolean.TRUE.equals(ready) && valid(entity, trip)) {
                    Optional<DoorVec3> point = DoorArrivals.findSafeNear(new DoorVec3(ticket.x(), ticket.y(), ticket.z()), 3,
                        candidate -> safe(entity, destination, candidate, true));
                    success = point.isPresent() && transitTeleport(entity, source.endpoint, ticket.sourceEndpointId(), destination, point.get(), ticket.yaw(), ticket.pitch(), trip.transit().carriesMomentum()
                        ? DoorVelocityTransform.rotateYaw(trip.transit().velocity(), ticket.yaw() - trip.transit().yaw()) : new DoorVec3(0, 0, 0));
                    if (success) {
                        removeTicket(entity, ticket);
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Pocket return failed for {}", entity.getUUID(), exception);
            } finally {
                if (flights.remove(entity.getUUID(), flight)) {
                    lease.close();
                }
                finishPocket(entity, trip, success);
            }
        }, server);
    }

    private boolean finishFlight(Entity entity, Flight flight, boolean success) {
        if (!flights.remove(entity.getUUID(), flight)) {
            return false;
        }
        try {
            Snapshot current = capture(flight.source().endpoint);
            DoorTransitGate.complete(flight.source().cycle, flight.transit(), success, current != null && current.active());
            return true;
        } finally {
            flight.lease().close();
        }
    }

    private ClientViewMessage.TravelBegin preparedCrossing(Entity entity) {
        return entity instanceof ServerPlayer && runtime.clientViews().crossing(entity.getUUID())
            ? runtime.clientViews().preparation(entity.getUUID()).orElse(null) : null;
    }

    private boolean valid(Entity entity, PocketTrip trip) {
        if (!enabled() || generation != trip.generation() || pocketTrips.get(entity.getUUID()) != trip
            || !entity.isAlive() || entity.level() != trip.level() || entity.position().distanceToSqr(trip.point()) > 1.0D
            || System.currentTimeMillis() >= trip.deadline() || !canEnter(entity, trip.source().endpoint)
            || trip.prepared() != null && !runtime.clientViews().crossing(entity.getUUID(), trip.prepared())) {
            return false;
        }
        Snapshot current = capture(trip.source().endpoint);
        return current != null && current.active() && current.plane().equals(trip.transit().sourcePlane())
            && state.findEndpointByItem(trip.source().endpoint.identity().itemId()).filter(trip.source().endpoint::equals).isPresent();
    }

    private void finishPocket(Entity entity, PocketTrip trip, boolean success) {
        if (closed || generation != trip.generation() || pocketTrips.get(entity.getUUID()) != trip) {
            return;
        }
        Snapshot current = capture(trip.source().endpoint);
        if (success && trip.transit().claimsOpenCycle() && current != null && !current.powered()) {
            setOpen(current, trip.source().endpoint.openState() != DoorOpenState.OPEN);
        }
        DoorTransitGate.complete(trip.source().cycle, trip.transit(), success, current != null && current.active());
        pocketTrips.remove(entity.getUUID(), trip);
        if (!success && entity instanceof ServerPlayer player && trip.prepared() != null) {
            runtime.clientViews().cancelPreparation(player, trip.prepared());
        }
    }

    void removeTicket(Entity actor, ReturnTicket expected) {
        mutate(actor, activeState -> activeState.getReturnTicket(actor.getUUID()).filter(expected::equals).isPresent()
            ? activeState.removeReturnTicket(actor.getUUID()) : Optional.empty(), ignored -> { }, () -> { });
    }

    boolean teleport(Entity entity, ServerLevel level, DoorVec3 point, float yaw, float pitch, DoorVec3 velocity) {
        return teleport(entity, new Arrival(level, point, yaw, pitch, velocity, Optional.empty()));
    }

    private boolean transitTeleport(Entity entity, PlacedDoorEndpoint source, UUID destinationId, ServerLevel level,
                                    DoorVec3 point, float yaw, float pitch, DoorVec3 velocity) {
        Optional<MinecraftTraversalContext> context = entity instanceof ServerPlayer player
            ? Optional.of(new MinecraftTraversalContext(UUID.randomUUID(), TraversalKind.DIMENSIONAL_DOOR, player,
                source.identity().itemId(), source.identity().kind().name(), MinecraftTraversalContext.Location.of(player),
                Optional.of(new MinecraftTraversalContext.Destination("", destinationId,
                    new MinecraftTraversalContext.Location(level, vector(point), yaw, pitch)))))
            : Optional.empty();
        return teleport(entity, new Arrival(level, point, yaw, pitch, velocity, context));
    }

    private boolean teleport(Entity entity, Arrival arrival) {
        ClientViewMessage.TravelBegin prepared = preparedCrossing(entity);
        ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket = entity instanceof ServerPlayer player
            ? runtime.preSend().preSend(player, arrival.level(), (int) Math.floor(arrival.point().x()), (int) Math.floor(arrival.point().z())) : null;
        MinecraftTravelCosts.Admission admission = null;
        ClientViewMessage.TravelCommit preparedCommit = null;
        Entity arrived = null;
        try {
            if (arrival.context().isPresent()) {
                admission = runtime.costs().open(arrival.context().get());
                if (!admission.allowed()) {
                    return false;
                }
            }
            if (entity instanceof ServerPlayer player && arrival.context().isPresent()) {
                preparedCommit = runtime.clientViews().commitTravel(player, arrival.context().get().portalId(), arrival.level(),
                    new ClientViewMessage.TravelPose(arrival.point().x(), arrival.point().y(), arrival.point().z(), arrival.yaw(), arrival.pitch()),
                    new GeometryVector(arrival.velocity().x(), arrival.velocity().y(), arrival.velocity().z()));
                if (prepared != null && preparedCommit == null) {
                    runtime.clientViews().cancelPreparation(player, prepared);
                    return false;
                }
            }
            try (WormholesModRuntime.TeleportScope scope = runtime.beginTeleport(entity)) {
                arrived = entity.teleport(new TeleportTransition(arrival.level(), vector(arrival.point()), vector(arrival.velocity()),
                    arrival.yaw(), arrival.pitch(), TeleportTransition.PLACE_PORTAL_TICKET));
            }
            if (arrived == null) {
                if (entity instanceof ServerPlayer player) {
                    runtime.clientViews().cancelTravel(player, preparedCommit);
                }
                return false;
            }
            if (admission != null) {
                admission.commit();
            }
            if (entity instanceof ServerPlayer player) {
                runtime.clientViews().completeTravel(player);
            }
        } finally {
            if (arrived == null) {
                try {
                    if (admission != null && admission.allowed()) {
                        admission.refund(TraversalRefundReason.TELEPORT_FAILED);
                    }
                } finally {
                    if (ticket != null) {
                        runtime.preSend().rollback(ticket);
                    }
                }
            }
        }
        arrived.resetFallDistance();
        cooldowns.put(arrived.getUUID(), System.currentTimeMillis() + TRANSIT_COOLDOWN_MILLIS);
        runtime.travelArrived(arrived);
        try {
            presentation.teleport(arrived, arrival.level(), preparedCommit != null);
        } catch (RuntimeException failure) {
            LOGGER.error("Could not play dimensional-door arrival sound for {}", arrived.getUUID(), failure);
        }
        return true;
    }

    private boolean arrive(Entity entity, ActiveDoor source, DoorTransit transit, Snapshot destination) {
        DoorwayPlane plane = destination.plane();
        int side = DoorPlanePairing.arrivalSideSign(transit.sourcePlane(), plane, transit.direction());
        DoorVec3 nominal = DoorArrivals.destinationPoint(plane, transit, side);
        boolean standing = transit.travelerClass() == DoorTravelerClass.LIVING && !plane.horizontal();
        DoorAutoCloseBook.Arrival open = autoClose.decideArrival(destination.endpoint().identity().itemId(), destination.active());
        boolean changedOpen = false;
        boolean moved = false;
        try {
            if (transit.preparedCrossing() != null && open == DoorAutoCloseBook.Arrival.OPEN) {
                setOpen(destination, destination.endpoint().openState() == DoorOpenState.OPEN);
                changedOpen = true;
            }
            Optional<DoorVec3> safe = transit.preparedCrossing() == null
                ? DoorArrivals.findSafeVerticalDoorStanding(nominal, DoorPlanePairing.arrivalYOffsets(plane, side),
                    point -> safe(entity, destination.level(), point, standing))
                : safe(entity, destination.level(), nominal, standing) ? Optional.of(nominal) : Optional.empty();
            if (safe.isEmpty()) {
                return false;
            }
            if (!changedOpen && open == DoorAutoCloseBook.Arrival.OPEN) {
                setOpen(destination, destination.endpoint().openState() == DoorOpenState.OPEN);
                changedOpen = true;
            }
            DoorVec3 point = safe.get();
            DoorVec3 velocity = !transit.carriesMomentum() ? new DoorVec3(0, 0, 0)
                : transit.preparedCrossing() != null ? DoorArrivals.destinationVelocity(plane, transit, side)
                    : DoorVelocityTransform.map(transit.sourcePlane(), plane, transit.velocity());
            DoorArrivals.Facing facing = DoorArrivals.destinationFacing(plane, transit, side);
            moved = transitTeleport(entity, source.endpoint, destination.endpoint().identity().itemId(), destination.level(), point,
                facing.yaw(), facing.pitch(), velocity);
            if (!moved) {
                return false;
            }
        } finally {
            if (!moved && changedOpen) {
                Snapshot current = capture(destination.endpoint());
                if (current != null && current.plane().equals(destination.plane()) && current.powered() == destination.powered()) {
                    setOpen(current, destination.open());
                }
            }
        }
        if (transit.claimsOpenCycle()) {
            Snapshot current = capture(source.endpoint);
            if (current != null && !current.powered()) {
                setOpen(current, source.endpoint.openState() != DoorOpenState.OPEN);
            }
        }
        if (open != DoorAutoCloseBook.Arrival.LEAVE) {
            if (transit.preparedCrossing() != null) {
                preparedArrivalBodies.computeIfAbsent(destination.endpoint().identity().itemId(), ignored -> new HashSet<>()).add(entity);
            }
            armClose(destination.endpoint(), autoClose.arm(destination.endpoint().identity().itemId()), 0);
        }
        return true;
    }

    private void armClose(PlacedDoorEndpoint endpoint, long token, int deferrals) {
        long startedGeneration = generation;
        runtime.schedule(() -> {
            if (closed || generation != startedGeneration) {
                return;
            }
            Snapshot current = capture(endpoint);
            DoorAutoCloseBook.Decision decision = autoClose.decide(endpoint.identity().itemId(), token,
                current != null && current.active(), doors.containsKey(endpoint.identity().itemId())
                    && doors.get(endpoint.identity().itemId()).cycle.phase() == DoorOpenCycle.Phase.IN_TRANSIT
                        || preparedArrivalOccupied(endpoint, current), deferrals);
            if (decision == DoorAutoCloseBook.Decision.DEFER) {
                armClose(endpoint, token, deferrals + 1);
            } else if (decision == DoorAutoCloseBook.Decision.CLOSE && current != null && !current.powered()) {
                preparedArrivalBodies.remove(endpoint.identity().itemId());
                setOpen(current, endpoint.openState() != DoorOpenState.OPEN);
            } else if (decision != DoorAutoCloseBook.Decision.SUPERSEDED) {
                preparedArrivalBodies.remove(endpoint.identity().itemId());
            }
        }, DoorAutoCloseBook.ARRIVAL_AUTO_CLOSE_TICKS);
    }

    private boolean preparedArrivalOccupied(PlacedDoorEndpoint endpoint, Snapshot current) {
        Set<Entity> travelers = preparedArrivalBodies.get(endpoint.identity().itemId());
        if (travelers == null || current == null) {
            return false;
        }
        AABB doorway = new AABB(current.block()).expandTowards(0, current.plane().horizontal() ? 0 : 1, 0);
        travelers.removeIf(traveler -> !traveler.isAlive() || traveler instanceof ServerPlayer player && player.hasDisconnected()
            || traveler.level() != current.level() || !traveler.getBoundingBox().intersects(doorway));
        if (travelers.isEmpty()) {
            preparedArrivalBodies.remove(endpoint.identity().itemId());
            return false;
        }
        return true;
    }

    private boolean canEnter(Entity entity, PlacedDoorEndpoint endpoint) {
        boolean object = entity instanceof ItemEntity || entity instanceof Projectile || entity instanceof ExperienceOrb;
        return DoorTravelerPolicy.canEnter(endpoint.identity().kind(), entity instanceof ServerPlayer,
            entity instanceof Mob || entity instanceof VehicleEntity, object, entity.getType() == EntityTypes.ENDER_DRAGON || entity.getType() == EntityTypes.WITHER, entity.getType() == EntityTypes.ENDER_DRAGON,
            entity.isPassenger() || entity.isVehicle() || entity instanceof Leashable leashable && leashable.isLeashed(),
            entity.getBbWidth(), entity.getBbHeight()) && canAccess(entity, endpoint);
    }

    private boolean canAccess(Entity entity, PlacedDoorEndpoint endpoint) {
        Entity responsible = entity instanceof Projectile projectile ? projectile.getOwner()
            : entity instanceof ItemEntity item ? item.getOwner() : entity;
        if (!(responsible instanceof ServerPlayer player) || player.hasDisconnected()) {
            return true;
        }
        return DoorAccessPolicy.canUse(state.accessRecord(endpoint.identity().itemId()).orElse(null), player.getUUID(),
            options.access().permission(player, DoorAccessPolicy.BYPASS_NODE));
    }

    public boolean canManage(ServerPlayer player, UUID itemId) {
        return enabled() && state.findEndpointByItem(itemId).isPresent() && state.accessRecord(itemId).isPresent() && DoorAccessPolicy.canManage(
            state.accessRecord(itemId).orElse(null), player.getUUID(), options.access().administrator(player));
    }

    public CompletableFuture<Boolean> updateOpenState(ServerPlayer player, UUID itemId, DoorOpenState openState) {
        return edit(player, itemId, (store, endpoint) -> store.setEndpointOpenState(endpoint.position(), openState));
    }

    public CompletableFuture<Boolean> updateProjection(ServerPlayer player, UUID itemId, DoorProjectionState projection) {
        return edit(player, itemId, (store, endpoint) -> store.setEndpointProjection(endpoint.position(), projection));
    }

    public CompletableFuture<Boolean> addAccessPlayer(ServerPlayer player, UUID itemId, UUID listedId) {
        return edit(player, itemId, (store, endpoint) -> {
            if (store.accessRecord(itemId).filter(record -> record.ownerId().equals(listedId)).isPresent()) {
                return false;
            }
            return store.addAccessPlayer(itemId, listedId);
        });
    }

    public CompletableFuture<Boolean> updateAccessState(ServerPlayer player, UUID itemId, AccessChange change) {
        return edit(player, itemId, (store, endpoint) -> store.setAccessState(itemId, change.playerId(), change.state()));
    }

    public CompletableFuture<Boolean> removeAccessPlayer(ServerPlayer player, UUID itemId, UUID listedId) {
        return edit(player, itemId, (store, endpoint) -> store.removeAccessPlayer(itemId, listedId));
    }

    private CompletableFuture<Boolean> edit(ServerPlayer player, UUID itemId, DoorMutation mutation) {
        runtime.requireServerThread();
        if (!canManage(player, itemId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Only the door owner or an administrator can edit this door"));
        }
        DoorStateService store = state;
        long startedGeneration = generation;
        return CompletableFuture.supplyAsync(() -> {
            PlacedDoorEndpoint endpoint = store.findEndpointByItem(itemId).orElseThrow(() -> new IllegalStateException("Door no longer exists"));
            try {
                return mutation.apply(store, endpoint);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, storage).thenApplyAsync(changed -> {
            if (closed || startedGeneration != generation) {
                return false;
            }
            store.findEndpointByItem(itemId).ifPresent(endpoint -> doors.put(itemId, new ActiveDoor(endpoint)));
            return changed;
        }, server).whenComplete((changed, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not edit dimensional door {}", itemId, failure);
            }
        });
    }

    public record AccessChange(UUID playerId, DoorAccessState state) {
    }

    @FunctionalInterface
    private interface DoorMutation {
        boolean apply(DoorStateService state, PlacedDoorEndpoint endpoint) throws IOException;
    }

    public List<DoorView> projectableViews() {
        runtime.requireServerThread();
        if (!enabled()) {
            return List.of();
        }
        List<DoorView> views = new ArrayList<>(doors.size());
        for (ActiveDoor door : doors.values()) {
            Snapshot snapshot = capture(door.endpoint);
            if (snapshot != null) {
                views.add(new DoorView(snapshot.endpoint(), snapshot.level(), snapshot.plane(), snapshot.active()));
            }
        }
        return List.copyOf(views);
    }

    public Optional<ProjectionDestination> projectionDestination(DoorView source, UUID observerId) {
        runtime.requireServerThread();
        if (closed) {
            return Optional.empty();
        }
        return switch (source.endpoint().identity().kind()) {
            case PAIR -> state.findMate(source.endpoint().identity()).flatMap(endpoint -> {
                Snapshot mate = capture(endpoint);
                if (mate == null) {
                    return Optional.empty();
                }
                DoorVec3 center = mate.plane().center();
                return Optional.of(new ProjectionDestination(endpoint.identity().itemId(), mate.level(),
                    new GeometryVector(center.x(), center.y(), center.z()), DoorApertureFrames.destinationFrame(source.plane(), mate.plane())));
            });
            case PERSONAL, PUBLIC -> pocketView(source, observerId);
            case RETURN -> state.getReturnTicket(observerId).flatMap(ticket -> {
                PlacedDoorEndpoint endpoint = state.findEndpointByItem(ticket.sourceEndpointId()).orElse(null);
                if (endpoint != null) {
                    ServerLevel currentLevel = level(endpoint.position().worldKey());
                    if (endpoint.identity().kind() == DoorKind.RETURN || currentLevel == null || isPocketLevel(currentLevel)) {
                        return Optional.empty();
                    }
                    EndpointProjection preparation = endpointProjections.get(endpoint.identity().itemId());
                    if (preparation != null) {
                        preparation.observers.add(observerId);
                    }
                    if (!currentLevel.hasChunk(endpoint.position().x() >> 4, endpoint.position().z() >> 4)) {
                        prepareEndpointProjection(endpoint, currentLevel, observerId);
                        return Optional.empty();
                    }
                    return endpointView(source, endpoint);
                }
                ServerLevel destination = level(ticket.sourceWorldKey());
                if (destination == null) {
                    return Optional.empty();
                }
                double yaw = Math.toRadians(ticket.yaw());
                Direction direction = Direction.closest(-Math.sin(yaw), 0, Math.cos(yaw)).reverse();
                return Optional.of(new ProjectionDestination(ticket.sourceEndpointId(), destination,
                    new GeometryVector(ticket.x(), ticket.y() + 1.0D, ticket.z()), PortalFrame.fromNormalUp(direction, Direction.U)));
            });
        };
    }

    private void prepareEndpointProjection(PlacedDoorEndpoint endpoint, ServerLevel level, UUID observer) {
        UUID id = endpoint.identity().itemId();
        EndpointProjection existing = endpointProjections.get(id);
        if (existing != null) {
            if (existing.endpoint.equals(endpoint)) {
                existing.observers.add(observer);
                return;
            }
            endpointProjections.remove(id, existing);
            existing.lease.close();
        }
        long now = System.currentTimeMillis();
        if (endpointProjections.size() >= 128 || now < endpointProjectionRetries.getOrDefault(id, 0L)) {
            return;
        }
        ChunkLease lease;
        try {
            lease = runtime.leases().retain(level, worldId(level), endpoint.position().x() >> 4, endpoint.position().z() >> 4);
        } catch (RuntimeException failure) {
            endpointProjectionRetries.put(id, now + 5_000L);
            LOGGER.error("Could not prepare dimensional-door projection {}", id, failure);
            return;
        }
        EndpointProjection preparation = new EndpointProjection(lease, endpoint);
        preparation.observers.add(observer);
        endpointProjections.put(id, preparation);
        long startedGeneration = generation;
        lease.ready().whenCompleteAsync((ready, failure) -> {
            if (closed || generation != startedGeneration || endpointProjections.get(id) != preparation) {
                lease.close();
                return;
            }
            if (failure != null || !Boolean.TRUE.equals(ready)) {
                endpointProjections.remove(id, preparation);
                endpointProjectionRetries.put(id, System.currentTimeMillis() + 5_000L);
                lease.close();
                if (failure != null) {
                    LOGGER.error("Could not prepare dimensional-door projection {}", id, failure);
                }
                return;
            }
            preparation.expiresAt = System.currentTimeMillis() + 5_000L;
        }, server);
    }

    private void expireEndpointProjections(long now) {
        endpointProjections.entrySet().removeIf(entry -> {
            EndpointProjection preparation = entry.getValue();
            if (now < preparation.expiresAt) {
                return false;
            }
            endpointProjectionRetries.put(entry.getKey(), now + 5_000L);
            preparation.lease.close();
            return true;
        });
        endpointProjectionRetries.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private Optional<ProjectionDestination> endpointView(DoorView source, PlacedDoorEndpoint endpoint) {
        Snapshot target = capture(endpoint);
        if (target == null) {
            return Optional.empty();
        }
        DoorVec3 center = target.plane().center();
        return Optional.of(new ProjectionDestination(endpoint.identity().itemId(), target.level(),
            new GeometryVector(center.x(), center.y(), center.z()), DoorApertureFrames.destinationFrame(source.plane(), target.plane())));
    }

    private Optional<ProjectionDestination> pocketView(DoorView source, UUID observerId) {
        PocketDoorDestination route = (PocketDoorDestination) state.resolveDestination(source.endpoint().identity(), observerId);
        ServerPlayer observer = server.getPlayerList().getPlayer(observerId);
        if (observer == null || observer.hasDisconnected() || !source.active() || !canAccess(observer, source.endpoint())) {
            return Optional.empty();
        }
        PocketBinding binding = route.binding();
        PocketSpace space = state.findPocket(binding).orElse(null);
        DoorPosition sourcePosition = source.endpoint().position();
        PocketSpace standingIn = spaceAt(source.level(), new BlockPos(sourcePosition.x(), sourcePosition.y(), sourcePosition.z()));
        if (!PocketRooms.allowsPocketEntry(isPocketLevel(source.level()), standingIn, space)) {
            return Optional.empty();
        }
        if (pocketPreviews.contains(binding) || space != null && (pocketOperations.busy(space.spaceId()) || pocketPreparations.containsKey(space.spaceId()))) {
            return Optional.empty();
        }
        if (space != null) {
            PlacedDoorEndpoint endpoint = state.findEndpointByItem(new PocketLayout(space).returnDoorIdentity().itemId()).orElse(null);
            if (endpoint != null) {
                Optional<ProjectionDestination> view = endpointView(source, endpoint);
                if (view.isPresent()) {
                    return view;
                }
            }
        }
        requestPocketPreview(source, observerId, route);
        return Optional.empty();
    }

    private void requestPocketPreview(DoorView source, UUID observerId, PocketDoorDestination route) {
        PocketBinding binding = route.binding();
        if (!enabled() || pocketPreviews.contains(binding)
            || System.currentTimeMillis() < pocketPreviewRetries.getOrDefault(binding, 0L)) {
            return;
        }
        MainConfig main = configuration.settings().getMain();
        PocketsConfig config = configuration.settings().getPockets();
        DoorPosition position = source.endpoint().position();
        PreviewAllocation allocation = new PreviewAllocation(observerId,
            new PocketShell(main.pocketRoomSize, main.pocketShellMaterial, main.pocketReturnDoorMaterial),
            new PocketRules(config.rulesDefaultMobs, config.rulesDefaultPvp, config.rulesDefaultKeepInventory,
                config.rulesDefaultFixedTime, PocketRules.BuildPolicy.parse(config.rulesDefaultBuild)), config.instanceReset,
            isPocketLevel(source.level()), spaceAt(source.level(), new BlockPos(position.x(), position.y(), position.z())));
        long startedGeneration = generation;
        DoorStateService persistentState = state;
        MinecraftServer owner = server;
        pocketPreviews.add(binding);
        CompletableFuture.supplyAsync(() -> {
            try {
                return allocatePreview(persistentState, route, allocation);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, storage).thenComposeAsync(space -> {
            if (closed || generation != startedGeneration) {
                return CompletableFuture.failedFuture(new IllegalStateException("Pocket preview was stopped"));
            }
            if (space == null) {
                return CompletableFuture.completedFuture(null);
            }
            index(space);
            return preparePocket(space);
        }, owner).whenCompleteAsync((space, error) -> {
            if (closed || generation != startedGeneration) {
                return;
            }
            pocketPreviews.remove(binding);
            if (error != null || space == null) {
                pocketPreviewRetries.put(binding, System.currentTimeMillis() + 5_000L);
                if (error != null) {
                    LOGGER.error("Could not prepare pocket preview for {}", binding, error);
                }
            } else {
                pocketPreviewRetries.remove(binding);
            }
        }, owner);
    }

    private static PocketSpace allocatePreview(DoorStateService store, PocketDoorDestination route, PreviewAllocation allocation) throws IOException {
        PocketSpace existing = store.findPocket(route.binding()).orElse(null);
        if (!PocketRooms.allowsPocketEntry(allocation.insidePocketWorld(), allocation.standingIn(), existing)) {
            return null;
        }
        PocketSpace space = existing == null
            ? store.replacePocket(store.getOrAllocatePocket(route.binding(), allocation.shell()).withRules(allocation.rules())) : existing;
        if (route.isInstanced() && space.instance() == null) {
            space = store.replacePocket(space.withTemplateName(route.instancedTemplate()).withInstance(
                PocketInstances.newInstance(route.instancedTemplate(), allocation.observerId(), allocation.instanceReset(), System.currentTimeMillis())
                    .withLastOccupied(0L)));
        }
        return space;
    }

    private record PreviewAllocation(UUID observerId, PocketShell shell, PocketRules rules, String instanceReset,
                                     boolean insidePocketWorld, PocketSpace standingIn) {
    }

    public record DoorView(PlacedDoorEndpoint endpoint, ServerLevel level, DoorwayPlane plane, boolean active) {
    }

    public record ProjectionDestination(UUID id, ServerLevel level, GeometryVector origin, PortalFrame frame) {
    }

    public PocketSpace spaceAt(ServerLevel level, BlockPos block) {
        PocketSpace indexed = isPocketLevel(level) ? pocketChunks.get(chunkKey(block.getX() >> 4, block.getZ() >> 4)) : null;
        PocketSpace space = indexed == null ? null : state.findPocketById(indexed.spaceId()).orElse(null);
        if (space == null) {
            return null;
        }
        if (new PocketLayout(space).contains(block.getX(), block.getY(), block.getZ())) {
            return space;
        }
        for (PocketRoom room : space.rooms()) {
            if (PocketRooms.layout(space, room).contains(block.getX(), block.getY(), block.getZ())) {
                return space;
            }
        }
        return null;
    }

    public boolean protectedBlock(ServerLevel level, BlockPos block) {
        if (!enabled()) {
            return false;
        }
        if (isPocketLevel(level) && pocketOperations != null && pocketOperations.protects(block)) {
            return true;
        }
        PocketSpace space = spaceAt(level, block);
        if (space == null) {
            return false;
        }
        if (pocketOperations != null && pocketOperations.busy(space.spaceId())) {
            return true;
        }
        if (new PocketLayout(space).isProtected(block.getX(), block.getY(), block.getZ())) {
            return true;
        }
        for (PocketRoom room : space.rooms()) {
            if (PocketRooms.layout(space, room).isProtected(block.getX(), block.getY(), block.getZ())) {
                return true;
            }
        }
        return false;
    }

    private boolean mayBuild(ServerPlayer player, BlockPos block) {
        if (protectedBlock(player.level(), block)) {
            return false;
        }
        PocketSpace space = spaceAt(player.level(), block);
        if (space == null) {
            return true;
        }
        UUID owner = PocketRosterService.ownerOf(space, state::accessRecord).orElse(null);
        PocketRole role = PocketRosterService.roleOf(space, owner, player.getUUID());
        return space.rules().allowsBuild(role);
    }

    void publishPocket(PocketSpace space) {
        runtime.requireServerThread();
        pocketChunks.entrySet().removeIf(entry -> entry.getValue().spaceId().equals(space.spaceId()));
        index(space);
        PlacedDoorEndpoint endpoint = state.findEndpointByItem(new PocketLayout(space).returnDoorIdentity().itemId()).orElse(null);
        if (endpoint != null) {
            doors.put(endpoint.identity().itemId(), new ActiveDoor(endpoint));
        }
    }

    private void index(PocketSpace space) {
        index(space, new PocketLayout(space));
        for (PocketRoom room : space.rooms()) {
            index(space, PocketRooms.layout(space, room));
        }
    }

    private void index(PocketSpace space, PocketLayout layout) {
        for (int x = layout.minX() >> 4; x <= layout.maxX() >> 4; x++) {
            for (int z = layout.minZ() >> 4; z <= layout.maxZ() >> 4; z++) {
                pocketChunks.put(chunkKey(x, z), space);
            }
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static boolean isPocketLevel(ServerLevel level) {
        return level.dimension().identifier().equals(Identifier.fromNamespaceAndPath("wormholes", "pockets"));
    }

    private Snapshot capture(PlacedDoorEndpoint endpoint) {
        ServerLevel level = level(endpoint.position().worldKey());
        DoorPosition position = endpoint.position();
        if (level == null || !level.hasChunk(position.x() >> 4, position.z() >> 4)) {
            return null;
        }
        BlockPos block = new BlockPos(position.x(), position.y(), position.z());
        BlockState data = level.getBlockState(block);
        if (!supports(data, endpoint.identity().form())) {
            return null;
        }
        boolean trapdoor = endpoint.identity().form() == DoorForm.TRAPDOOR;
        if (!trapdoor && (data.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
            || !level.getBlockState(block.above()).is(data.getBlock()))) {
            return null;
        }
        Direction direction = direction(data.getValue(BlockStateProperties.HORIZONTAL_FACING));
        DoorHalf half = trapdoor && data.getValue(TrapDoorBlock.HALF) == Half.TOP ? DoorHalf.TOP : DoorHalf.BOTTOM;
        DoorwayPlane plane = new DoorwayPlane(position.x(), position.y(), position.z(), direction,
            endpoint.identity().form(), half, endpoint.openState());
        return new Snapshot(endpoint, level, block, plane, data.getValue(BlockStateProperties.OPEN),
            data.getValue(BlockStateProperties.POWERED));
    }

    private void setOpen(Snapshot door, boolean open) {
        BlockState state = door.level().getBlockState(door.block());
        if (!state.hasProperty(BlockStateProperties.OPEN) || state.getValue(BlockStateProperties.OPEN) == open) {
            return;
        }
        door.level().setBlock(door.block(), state.setValue(BlockStateProperties.OPEN, open), Block.UPDATE_CLIENTS);
        if (state.getBlock() instanceof DoorBlock) {
            BlockState upper = door.level().getBlockState(door.block().above());
            if (upper.is(state.getBlock()) && upper.hasProperty(DoorBlock.OPEN)) {
                door.level().setBlock(door.block().above(), upper.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS);
            }
        }
        try {
            presentation.swing(door.level(), door.block(), state, open);
        } catch (RuntimeException failure) {
            LOGGER.error("Could not play dimensional-door swing sound for {}", door.endpoint().identity().itemId(), failure);
        }
    }

    static boolean safe(Entity entity, ServerLevel level, DoorVec3 point, boolean standing) {
        AABB bounds = entity.getBoundingBox().move(point.x() - entity.getX(), point.y() - entity.getY(), point.z() - entity.getZ());
        for (int x = (int) Math.floor(bounds.minX) >> 4; x <= (int) Math.floor(bounds.maxX) >> 4; x++) {
            for (int z = (int) Math.floor(bounds.minZ) >> 4; z <= (int) Math.floor(bounds.maxZ) >> 4; z++) {
                if (!level.hasChunk(x, z)) {
                    return false;
                }
            }
        }
        if (bounds.minY <= level.getMinY() || bounds.maxY >= level.getMaxY()
            || !level.getWorldBorder().isWithinBounds(bounds) || !level.noCollision(entity, bounds)) {
            return false;
        }
        if (!standing) {
            return true;
        }
        int minX = (int) Math.floor(bounds.minX + 1.0E-7D);
        int maxX = (int) Math.floor(bounds.maxX - 1.0E-7D);
        int minY = (int) Math.floor(bounds.minY);
        int maxY = (int) Math.floor(bounds.maxY - 1.0E-7D);
        int minZ = (int) Math.floor(bounds.minZ + 1.0E-7D);
        int maxZ = (int) Math.floor(bounds.maxZ - 1.0E-7D);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos floor = new BlockPos(x, minY - 1, z);
                if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) {
                    return false;
                }
                for (int y = minY - 1; y <= maxY; y++) {
                    if (hazard(level.getBlockState(new BlockPos(x, y, z)))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean hazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
            || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE);
    }

    ServerLevel level(String key) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(key)));
    }

    private static BlockPos lower(ServerLevel level, BlockPos block) {
        BlockState state = level.getBlockState(block);
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? block.below() : block;
    }

    private static boolean supports(BlockState block, DoorForm form) {
        return form == DoorForm.DOOR ? block.getBlock() instanceof DoorBlock : block.getBlock() instanceof TrapDoorBlock;
    }

    private static DoorPosition position(ServerLevel level, BlockPos block) {
        return new DoorPosition(worldId(level), level.dimension().identifier().toString(), block.getX(), block.getY(), block.getZ());
    }

    static UUID worldId(ServerLevel level) {
        return UUID.nameUUIDFromBytes(level.dimension().identifier().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static DoorVec3 vector(Vec3 value) {
        return new DoorVec3(value.x, value.y, value.z);
    }

    private static Vec3 vector(DoorVec3 value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static void give(ServerPlayer player, ItemStack item) {
        if (!player.getInventory().add(item)) {
            player.drop(item, false, Prediction.SERVER_ONLY);
        }
    }

    private <T> void mutate(Entity actor, Mutation<T> mutation, Consumer<T> success, Runnable failure) {
        long startedGeneration = generation;
        DoorStateService persistentState = state;
        CompletableFuture.supplyAsync(() -> {
            try {
                return mutation.apply(persistentState);
            } catch (IOException | RuntimeException exception) {
                throw new CompletionException(exception);
            }
        }, storage).whenCompleteAsync((result, error) -> {
            if (closed || generation != startedGeneration) {
                if (error != null) {
                    LOGGER.error("Dimensional-door persistence failed during shutdown", error);
                }
                return;
            }
            if (error != null) {
                LOGGER.error("Could not persist dimensional-door operation for {}", actor.getUUID(), error);
                failure.run();
                if (!closed && actor instanceof ServerPlayer player && !player.hasDisconnected()) {
                    player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_ALREADY_PLACED, Map.of()));
                }
                return;
            }
            if (!closed) {
                success.accept(result);
            }
        }, server);
    }

    public record Options(Path directory, Access access) {
        public Options {
            Objects.requireNonNull(directory);
            Objects.requireNonNull(access);
        }
    }

    public interface Access {
        boolean administrator(ServerPlayer player);
        boolean permission(ServerPlayer player, String node);
    }

    @FunctionalInterface
    private interface Mutation<T> {
        T apply(DoorStateService state) throws IOException;
    }

    private static final class ActiveDoor {
        private final PlacedDoorEndpoint endpoint;
        private final DoorOpenCycle cycle = new DoorOpenCycle();

        private ActiveDoor(PlacedDoorEndpoint endpoint) {
            this.endpoint = endpoint;
        }
    }

    private record Arrival(ServerLevel level, DoorVec3 point, float yaw, float pitch, DoorVec3 velocity,
                           Optional<MinecraftTraversalContext> context) {
    }

    private record Snapshot(PlacedDoorEndpoint endpoint, ServerLevel level, BlockPos block, DoorwayPlane plane, boolean open, boolean powered) {
        boolean active() {
            return endpoint.openState().matches(open);
        }
    }

    private record PocketTrip(ActiveDoor source, DoorTransit transit, Level level, Vec3 point, long generation,
                              long deadline, ReturnTicket ticket, ClientViewMessage.TravelBegin prepared) { }
    private record Position(ServerLevel level, Vec3 point) { }
    private record DeferredCrossing(ServerPlayer player, ActiveDoor source, ServerLevel level, DoorTransit transit) { }
    private static final class EndpointProjection {
        private final ChunkLease lease;
        private final PlacedDoorEndpoint endpoint;
        private final Set<UUID> observers = new HashSet<>();
        private long expiresAt = System.currentTimeMillis() + 30_000L;

        private EndpointProjection(ChunkLease lease, PlacedDoorEndpoint endpoint) {
            this.lease = lease;
            this.endpoint = endpoint;
        }
    }

    private record Flight(ChunkLease lease, Level level, Vec3 point, long expiresAt, ActiveDoor source, DoorTransit transit) { }
}
