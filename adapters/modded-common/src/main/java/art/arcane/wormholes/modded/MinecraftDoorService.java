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
import art.arcane.wormholes.portal.PortalFrame;
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
    private Options options;
    private ExecutorService storage;
    private MinecraftServer server;
    private volatile WormholesModConfiguration configuration;
    private long generation;
    private final Map<UUID, ActiveDoor> doors = new LinkedHashMap<>();
    private final Set<UUID> pendingItems = new HashSet<>();
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Position> positions = new HashMap<>();
    private final DoorAutoCloseBook autoClose = new DoorAutoCloseBook();
    private DoorStateService state;
    private MinecraftPocketRooms pockets;
    private MinecraftPocketRules rules;
    private MinecraftPocketService pocketOperations;
    private MinecraftDoorMenus menus;
    private MinecraftDoorPresentation presentation;
    private final Map<UUID, PocketTrip> pocketTrips = new HashMap<>();
    private final Map<Long, PocketSpace> pocketChunks = new HashMap<>();
    private volatile boolean closed;

    public MinecraftDoorService(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
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
        menus = new MinecraftDoorMenus(runtime, this);
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

    public boolean canCraft(ServerPlayer player) {
        return enabled() && (options.access().administrator(player) || options.access().permission(player, DoorAccessPolicy.CRAFT_NODE));
    }

    public boolean resetAllowed() {
        runtime.requireServerThread();
        if (!flights.isEmpty() || !pocketTrips.isEmpty()) {
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
            menus.open(player, clicked.identity().itemId());
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
                    blockItem.useOn(new UseOnContext(player, hand, hit));
                    placed = capture(endpoint) != null;
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
        ItemStack refund = player.getItemInHand(hand).copyWithCount(player.hasInfiniteMaterials() ? 0 : 1);
        boolean[] placed = {false};
        pocketOperations.grow(player, endpoint, plane, () -> {
            if (closed || player.hasDisconnected() || player.level() != level
                || !MinecraftDoorItems.identity(player.getItemInHand(hand)).filter(endpoint.identity()::equals).isPresent()
                || !new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit).canPlace()) {
                return false;
            }
            item.useOn(new UseOnContext(player, hand, hit));
            placed[0] = capture(endpoint) != null;
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
        if (!enabled()) {
            presentation.clear();
            menus.tick();
            return;
        }
        presentation.tick();
        rules.tick();
        menus.tick();
        pocketOperations.tick();
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        Set<UUID> visited = new HashSet<>();
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
                if (visited.contains(entity.getUUID()) || travelling(entity.getUUID()) || cooldowns.containsKey(entity.getUUID())
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
                depart(entity, door, snapshot, transit);
            }
        }
        positions.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            positions.put(player.getUUID(), new Position(player.level(), player.position()));
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
            }
        }, server);
    }

    public void playerDisconnected(ServerPlayer player) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        rules.playerDisconnected(player);
        menus.disconnected(player);
        presentation.disconnected(player);
        Flight flight = flights.remove(player.getUUID());
        if (flight != null) {
            flight.lease.close();
        }
        pocketTrips.remove(player.getUUID());
        positions.remove(player.getUUID());
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
        pocketTrips.clear();
        pocketChunks.clear();
        if (presentation != null) {
            presentation.close();
            presentation = null;
        }
        if (menus != null) {
            menus.close();
            menus = null;
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
        Flight flight = new Flight(lease, entity.level(), entity.position(), System.currentTimeMillis() + 30_000L);
        flights.put(entity.getUUID(), flight);
        long startedGeneration = generation;
        lease.ready().whenCompleteAsync((ready, error) -> {
            if (closed || generation != startedGeneration) {
                lease.close();
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
                Snapshot current = capture(source.endpoint);
                DoorTransitGate.complete(source.cycle, transit, success, current != null && current.active());
                flights.remove(entity.getUUID(), flight);
                lease.close();
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
            ticket = new ReturnTicket(entity.getUUID(), source.endpoint.identity().itemId(), worldId(snapshot.level()),
                snapshot.level().dimension().identifier().toString(), returnPoint.x(), returnPoint.y(), returnPoint.z(),
                DoorArrivals.arrivalYaw(snapshot.plane(), snapshot.plane(), transit), transit.pitch());
        }
        if (transit.claimsOpenCycle() && !source.cycle.tryBegin(snapshot.active())) {
            return;
        }
        PocketTrip trip = new PocketTrip(source, transit, entity.level(), entity.position(), generation,
            System.currentTimeMillis() + 30_000L, ticket);
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
            pockets.prepare(space).whenCompleteAsync((room, error) -> {
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
                pocketOperations.furnish(space).whenCompleteAsync((furnished, failure) -> {
                    if (failure != null || !valid(entity, trip)) {
                        if (failure != null) {
                            LOGGER.error("Could not furnish pocket {}", space.spaceId(), failure);
                        }
                        room.close();
                        finishPocket(entity, trip, false);
                        return;
                    }
                    persistPocketEntry(entity, trip, room);
                }, server);
            }, server);
        }, () -> finishPocket(entity, trip, false));
    }

    private void persistPocketEntry(Entity entity, PocketTrip trip, MinecraftPocketRooms.Prepared room) {
        PlacedDoorEndpoint endpoint = room.endpoint();
        mutate(entity, activeState -> {
            activeState.registerEndpoint(endpoint);
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
                        success = transitTeleport(entity, trip.source().endpoint, endpoint.identity().itemId(), room.level(), point, trip.transit().yaw(), trip.transit().pitch(),
                            trip.transit().carriesMomentum() ? trip.transit().velocity() : new DoorVec3(0, 0, 0));
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
            System.currentTimeMillis() + 30_000L, ticket);
        pocketTrips.put(entity.getUUID(), trip);
        ChunkLease lease;
        try {
            lease = runtime.leases().retain(destination, worldId(destination), (int) Math.floor(ticket.x()) >> 4, (int) Math.floor(ticket.z()) >> 4);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not prepare pocket return for {}", entity.getUUID(), exception);
            finishPocket(entity, trip, false);
            return;
        }
        Flight flight = new Flight(lease, entity.level(), entity.position(), trip.deadline());
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
                    success = point.isPresent() && transitTeleport(entity, source.endpoint, ticket.sourceEndpointId(), destination, point.get(), ticket.yaw(), ticket.pitch(), new DoorVec3(0, 0, 0));
                    if (success) {
                        removeTicket(entity, ticket);
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Pocket return failed for {}", entity.getUUID(), exception);
            } finally {
                flights.remove(entity.getUUID(), flight);
                lease.close();
                finishPocket(entity, trip, success);
            }
        }, server);
    }

    private boolean valid(Entity entity, PocketTrip trip) {
        if (!enabled() || generation != trip.generation() || pocketTrips.get(entity.getUUID()) != trip
            || !entity.isAlive() || entity.level() != trip.level() || entity.position().distanceToSqr(trip.point()) > 1.0D
            || System.currentTimeMillis() >= trip.deadline() || !canEnter(entity, trip.source().endpoint)) {
            return false;
        }
        Snapshot current = capture(trip.source().endpoint);
        return current != null && current.active() && current.plane().equals(trip.transit().sourcePlane())
            && state.findEndpointByItem(trip.source().endpoint.identity().itemId()).filter(trip.source().endpoint::equals).isPresent();
    }

    private void finishPocket(Entity entity, PocketTrip trip, boolean success) {
        if (closed || generation != trip.generation()) {
            return;
        }
        Snapshot current = capture(trip.source().endpoint);
        if (success && trip.transit().claimsOpenCycle() && current != null && !current.powered()) {
            setOpen(current, trip.source().endpoint.openState() != DoorOpenState.OPEN);
        }
        DoorTransitGate.complete(trip.source().cycle, trip.transit(), success, current != null && current.active());
        pocketTrips.remove(entity.getUUID(), trip);
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
        ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket = entity instanceof ServerPlayer player
            ? runtime.preSend().preSend(player, arrival.level(), (int) Math.floor(arrival.point().x()), (int) Math.floor(arrival.point().z())) : null;
        MinecraftTravelCosts.Admission admission = null;
        Entity arrived = null;
        try {
            if (arrival.context().isPresent()) {
                admission = runtime.costs().open(arrival.context().get());
                if (!admission.allowed()) {
                    return false;
                }
            }
            arrived = entity.teleport(new TeleportTransition(arrival.level(), vector(arrival.point()), vector(arrival.velocity()),
                arrival.yaw(), arrival.pitch(), TeleportTransition.PLACE_PORTAL_TICKET));
            if (arrived == null) {
                return false;
            }
            if (admission != null) {
                admission.commit();
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
        positions.remove(arrived.getUUID());
        try {
            presentation.teleport(arrived, arrival.level());
        } catch (RuntimeException failure) {
            LOGGER.error("Could not play dimensional-door arrival sound for {}", arrived.getUUID(), failure);
        }
        return true;
    }

    private boolean arrive(Entity entity, ActiveDoor source, DoorTransit transit, Snapshot destination) {
        DoorwayPlane plane = destination.plane();
        int side = DoorPlanePairing.arrivalSideSign(transit.sourcePlane(), plane, transit.direction());
        DoorVec3 nominal = DoorArrivals.arrivalPoint(plane, transit, side);
        Optional<DoorVec3> safe = DoorArrivals.findSafeVerticalDoorStanding(nominal,
            DoorPlanePairing.arrivalYOffsets(plane, side), point -> safe(entity, destination.level(), point,
                transit.travelerClass() == DoorTravelerClass.LIVING && !plane.horizontal()));
        if (safe.isEmpty()) {
            return false;
        }
        DoorAutoCloseBook.Arrival open = autoClose.decideArrival(destination.endpoint().identity().itemId(), destination.active());
        if (open == DoorAutoCloseBook.Arrival.OPEN) {
            setOpen(destination, destination.endpoint().openState() == DoorOpenState.OPEN);
        }
        DoorVec3 point = safe.get();
        DoorVec3 velocity = transit.carriesMomentum() ? DoorVelocityTransform.map(transit.sourcePlane(), plane, transit.velocity()) : new DoorVec3(0, 0, 0);
        boolean moved = false;
        try {
            moved = transitTeleport(entity, source.endpoint, destination.endpoint().identity().itemId(), destination.level(), point,
                DoorArrivals.arrivalYaw(transit.sourcePlane(), plane, transit), transit.pitch(), velocity);
            if (!moved) {
                return false;
            }
        } finally {
            if (!moved && open == DoorAutoCloseBook.Arrival.OPEN) {
                setOpen(destination, destination.open());
            }
        }
        if (transit.claimsOpenCycle()) {
            Snapshot current = capture(source.endpoint);
            if (current != null && !current.powered()) {
                setOpen(current, source.endpoint.openState() != DoorOpenState.OPEN);
            }
        }
        if (open != DoorAutoCloseBook.Arrival.LEAVE) {
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
                    && doors.get(endpoint.identity().itemId()).cycle.phase() == DoorOpenCycle.Phase.IN_TRANSIT, deferrals);
            if (decision == DoorAutoCloseBook.Decision.DEFER) {
                armClose(endpoint, token, deferrals + 1);
            } else if (decision == DoorAutoCloseBook.Decision.CLOSE && current != null && !current.powered()) {
                setOpen(current, endpoint.openState() != DoorOpenState.OPEN);
            }
        }, DoorAutoCloseBook.ARRIVAL_AUTO_CLOSE_TICKS);
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
            case PERSONAL -> pocketView(PocketBinding.personal(observerId));
            case PUBLIC -> pocketView(((PocketDoorDestination) state.resolveDestination(source.endpoint().identity(), observerId)).binding());
            case RETURN -> state.getReturnTicket(observerId).flatMap(ticket -> {
                ServerLevel destination = level(ticket.sourceWorldKey());
                if (destination == null) {
                    return Optional.empty();
                }
                double yaw = Math.toRadians(ticket.yaw());
                Direction direction = Direction.closest(-Math.sin(yaw), 0, Math.cos(yaw)).reverse();
                return Optional.of(new ProjectionDestination(ticket.sourceEndpointId(), destination,
                    new GeometryVector(ticket.x(), ticket.y(), ticket.z()), PortalFrame.fromNormalUp(direction, Direction.U)));
            });
        };
    }

    private Optional<ProjectionDestination> pocketView(PocketBinding binding) {
        ServerLevel destination = level("wormholes:pockets");
        PocketSpace space = state.findPocket(binding).orElse(null);
        if (destination == null || space == null || pocketOperations.busy(space.spaceId())) {
            return Optional.empty();
        }
        PocketEntryCoordinates entry = new PocketLayout(space).entry();
        return Optional.of(new ProjectionDestination(space.spaceId(), destination,
            new GeometryVector(entry.x(), entry.y(), entry.z()), PortalFrame.fromNormalUp(Direction.S, Direction.U)));
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
            player.drop(item, false);
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
                              long deadline, ReturnTicket ticket) { }
    private record Position(ServerLevel level, Vec3 point) { }
    private record Flight(ChunkLease lease, Level level, Vec3 point, long expiresAt) { }
}
