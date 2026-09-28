package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.DoorPairIdentity;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PairEndpoint;
import art.arcane.wormholes.door.PocketRoom;
import art.arcane.wormholes.door.PocketRooms;
import art.arcane.wormholes.door.PocketBlockPosition;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.PocketBinding;
import art.arcane.wormholes.door.PocketDoorDestination;
import art.arcane.wormholes.door.PocketInstances;
import art.arcane.wormholes.config.toml.PocketsConfig;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.localization.PocketsMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.door.PocketMutationIntent;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.PocketResizeOutcome;
import art.arcane.wormholes.door.PocketResizePolicy;
import art.arcane.wormholes.door.PocketResizeWorkflow;
import art.arcane.wormholes.door.PocketShell;
import art.arcane.wormholes.door.PocketMutationJournal;
import art.arcane.wormholes.door.PocketRosterService;
import art.arcane.wormholes.door.PocketRole;
import art.arcane.wormholes.door.PocketRules;
import art.arcane.wormholes.door.PocketSnapshots;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.wormholes.door.PocketTemplateService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

public final class MinecraftPocketService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final int CLEAR_PER_TICK = 2048;

    private final WormholesModRuntime runtime;
    private final MinecraftDoorService doors;
    private final MinecraftServer server;
    private final WormholesModConfiguration configuration;
    private final DoorStateService state;
    private final MinecraftPocketRooms rooms;
    private final Executor storage;
    private final ExecutorService profiles = Executors.newVirtualThreadPerTaskExecutor();
    private final MinecraftStructureIo io;
    private final MinecraftPocketTemplates templates;
    private final PocketSnapshots<StructureTemplate> snapshots;
    private final PocketInstances instances;
    private final Map<String, Boolean> instancedTemplates = new ConcurrentHashMap<>();
    private long nextInstanceSweep;
    private final PocketMutationJournal journal;
    private final PocketRosterService roster;
    private final MinecraftPocketResize resizes;
    private final PocketResizeWorkflow resizeWorkflow;
    private final Path pendingStructures;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, PocketLayout> mutationBounds = new HashMap<>();
    private volatile boolean closed;

    MinecraftPocketService(WormholesModRuntime runtime, Options options) throws IOException {
        this.runtime = runtime;
        doors = options.doors();
        server = runtime.server();
        configuration = runtime.configuration();
        state = doors.state();
        rooms = options.rooms();
        storage = options.storage();
        io = new MinecraftStructureIo(BuiltInRegistries.BLOCK);
        templates = new MinecraftPocketTemplates(options.directory(), configuration, io);
        snapshots = new PocketSnapshots<>(options.directory(), io);
        instances = new PocketInstances(templates);
        state.attachInstances(name -> Boolean.TRUE.equals(instancedTemplates.get(name)));
        journal = PocketMutationJournal.under(options.directory(), MinecraftJsonDocuments.INSTANCE);
        resizes = new MinecraftPocketResize(runtime, doors, rooms);
        resizeWorkflow = new PocketResizeWorkflow(journal);
        roster = new PocketRosterService(() -> state, state::accessRecord);
        pendingStructures = options.directory().resolve("doors/pending-structures");
        List<PocketMutationIntent> interrupted = journal.load();
        runtime.schedule(() -> recover(interrupted), 1L);
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<MinecraftPocketService> service) {
        dispatcher.register(Commands.literal("wormholes")
            .then(Commands.literal("pocket").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("info").executes(context -> service.get().info(context.getSource())))
                .then(Commands.literal("instance").then(Commands.literal("reset").executes(context -> service.get().resetCommand(context.getSource()))))
                .then(resizeCommands("resize", service))
                .then(resizeCommands("resizeall", service))
                .then(Commands.literal("template")
                    .then(Commands.literal("list").executes(context -> service.get().list(context.getSource())))
                    .then(Commands.literal("apply").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> service.get().applyCommand(context.getSource(), StringArgumentType.getString(context, "name"), false))
                        .then(Commands.argument("confirm", BoolArgumentType.bool()).executes(context -> service.get().applyCommand(
                            context.getSource(), StringArgumentType.getString(context, "name"), BoolArgumentType.getBool(context, "confirm")))))))
                .then(Commands.literal("snapshot").executes(context -> service.get().snapshotCommand(context.getSource(), PocketSnapshots.LATEST))
                    .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> service.get().snapshotCommand(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("restore").executes(context -> service.get().restoreCommand(context.getSource(), PocketSnapshots.LATEST, false))
                    .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> service.get().restoreCommand(context.getSource(), StringArgumentType.getString(context, "name"), false))
                    .then(Commands.argument("confirm", BoolArgumentType.bool()).executes(context -> service.get().restoreCommand(
                        context.getSource(), StringArgumentType.getString(context, "name"), BoolArgumentType.getBool(context, "confirm"))))))
                .then(Commands.literal("rules")
                    .then(Commands.literal("mobs").then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> service.get().rule(context.getSource(), "mobs", BoolArgumentType.getBool(context, "enabled")))))
                    .then(Commands.literal("pvp").then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> service.get().rule(context.getSource(), "pvp", BoolArgumentType.getBool(context, "enabled")))))
                    .then(Commands.literal("keep-inventory").then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> service.get().rule(context.getSource(), "keep-inventory", BoolArgumentType.getBool(context, "enabled")))))
                    .then(Commands.literal("fixed-time").then(Commands.argument("ticks", IntegerArgumentType.integer(-1, 23999))
                        .executes(context -> service.get().time(context.getSource(), IntegerArgumentType.getInteger(context, "ticks")))))
                    .then(Commands.literal("build").then(Commands.argument("policy", StringArgumentType.word())
                        .executes(context -> service.get().build(context.getSource(), StringArgumentType.getString(context, "policy"))))))
                .then(Commands.literal("roster")
                    .then(Commands.literal("add").then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> service.get().assign(context.getSource(), StringArgumentType.getString(context, "player"), "visitor", PocketsMessages.ROSTER_ADDED))
                        .then(Commands.argument("role", StringArgumentType.word()).executes(context -> service.get().assign(context.getSource(),
                            StringArgumentType.getString(context, "player"), StringArgumentType.getString(context, "role"), PocketsMessages.ROSTER_ADDED)))))
                    .then(Commands.literal("role").then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("role", StringArgumentType.word()).executes(context -> service.get().assign(context.getSource(),
                            StringArgumentType.getString(context, "player"), StringArgumentType.getString(context, "role"), PocketsMessages.ROSTER_ROLE)))))
                    .then(Commands.literal("remove").then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> service.get().remove(context.getSource(), StringArgumentType.getString(context, "player"))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resizeCommands(String name, Supplier<MinecraftPocketService> service) {
        ResizeCommand command = new ResizeCommand(service, name.equals("resizeall"));
        return Commands.literal(name).executes(context -> command.run(context, 0))
            .then(Commands.argument("size", IntegerArgumentType.integer(0, PocketShell.MAX_SIZE)).executes(context -> command.run(context, 1))
                .then(Commands.argument("material", StringArgumentType.word()).executes(context -> command.run(context, 2))
                    .then(Commands.argument("door", StringArgumentType.word()).executes(context -> command.run(context, 3))
                        .then(Commands.argument("confirm", BoolArgumentType.bool()).executes(context -> command.run(context, 4))))));
    }

    PocketDoorDestination destination(DoorItemIdentity identity, UUID traveler) {
        state.findPocket(PocketBinding.publicDoor(identity.itemId())).ifPresent(space -> refreshInstanceTemplate(space.templateName()));
        return (PocketDoorDestination) state.resolveDestination(identity, traveler);
    }

    public void tick() {
        runtime.requireServerThread();
        long now = System.currentTimeMillis();
        if (closed || now < nextInstanceSweep) {
            return;
        }
        nextInstanceSweep = now + 20_000L;
        read(() -> {
            Set<String> names = new HashSet<>();
            for (PocketSpace space : state.spaces()) {
                if (names.add(space.templateName())) {
                    refreshInstanceTemplate(space.templateName());
                }
            }
            return true;
        }).whenCompleteAsync((updated, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not refresh pocket template instance settings", failure);
            }
            if (!closed) {
                sweepInstances(now);
            }
        }, server);
    }

    public void sweepInstances(long now) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        Set<UUID> occupied = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PocketSpace space = doors.spaceAt(player.level(), player.blockPosition());
            if (space != null) {
                occupied.add(space.spaceId());
            }
        }
        List<PocketSpace> live = new ArrayList<>();
        Set<UUID> protectedInstances = new HashSet<>(occupied);
        PocketsConfig settings = configuration.settings().getPockets();
        for (PocketSpace space : state.spaces()) {
            if (space.instance() == null) {
                continue;
            }
            live.add(space);
            boolean entering = now - space.instance().lastOccupiedMillis() < PocketInstances.ENTRY_GRACE_MILLIS;
            if (entering || busy(space.spaceId())) {
                protectedInstances.add(space.spaceId());
            }
            if (PocketInstances.shouldReset(space.instance(), protectedInstances.contains(space.spaceId()), now, settings.instanceResetSeconds)) {
                resetIdleInstance(space);
            }
        }
        for (PocketSpace space : PocketInstances.evictionOrder(live, protectedInstances, settings.instanceMaxLive)) {
            if (!busy(space.spaceId())) {
                resetIdleInstance(space);
            }
        }
    }

    public CompletableFuture<PocketSpace> resetInstance(PocketSpace space) {
        runtime.requireServerThread();
        if (space.instance() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Pocket is not an instance: " + space.spaceId()));
        }
        return apply(space, space.instance().templateName(), true).thenCompose(updated -> read(() -> {
            PocketSpace current = current(updated);
            return state.replacePocket(current.withInstance(current.instance().withLastReset(System.currentTimeMillis())));
        }));
    }

    private void resetIdleInstance(PocketSpace space) {
        resetInstance(space).whenComplete((updated, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not reset idle pocket instance {}", space.spaceId(), failure);
            }
        });
    }

    private void refreshInstanceTemplate(String name) {
        if (!name.isEmpty()) {
            instancedTemplates.put(name, instances.isInstanced(name));
        }
    }

    private int resetCommand(CommandSourceStack source) {
        PocketSpace space = standing(source);
        if (space.instance() == null) {
            send(source, PocketsMessages.INSTANCE_NONE, Map.of());
            return 0;
        }
        return report(source, resetInstance(space), new Feedback(PocketsMessages.INSTANCE_RESET, Map.of("space", space.spaceId())));
    }

    public PocketTemplateService<StructureTemplate> templates() {
        return templates;
    }

    public PocketSnapshots<StructureTemplate> snapshots() {
        return snapshots;
    }

    public boolean busy(UUID spaceId) {
        return busy.contains(spaceId) || journal.pending(spaceId).isPresent();
    }

    boolean protects(BlockPos point) {
        for (PocketLayout layout : mutationBounds.values()) {
            if (layout.contains(point.getX(), point.getY(), point.getZ())) {
                return true;
            }
        }
        return false;
    }

    CompletableFuture<PocketSpace> furnish(PocketSpace space) {
        if (state.findEndpointByItem(new PocketLayout(space).returnDoorIdentity().itemId()).isPresent()) {
            return CompletableFuture.completedFuture(space);
        }
        String template = space.templateName().isEmpty() ? configuration.settings().getPockets().defaultTemplate.trim() : space.templateName();
        if (template.isEmpty()) {
            return CompletableFuture.completedFuture(space);
        }
        return locked(space, () -> read(() -> templates.load(template)).thenComposeAsync(structure -> {
            if (structure.isEmpty()) {
                LOGGER.warn("Pocket template {} is missing; pocket {} starts as a plain room", template, space.spaceId());
                return CompletableFuture.completedFuture(space);
            }
            return begin(space, structure.get(), template, false);
        }, server));
    }

    CompletableFuture<PocketSpace> grow(ServerPlayer player, PlacedDoorEndpoint placed, DoorwayPlane plane, BooleanSupplier placement) {
        runtime.requireServerThread();
        PocketSpace space = doors.spaceAt(player.level(), new BlockPos(placed.position().x(), placed.position().y(), placed.position().z()));
        if (space == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A room door must be placed inside a pocket"));
        }
        PocketRoom parent = PocketRooms.roomAt(space, placed.position().x(), placed.position().z()).orElse(null);
        Direction wall = PocketRooms.wallBehind(space, parent, placed.position(), plane).orElse(null);
        if (wall == null || !roster.roleOf(space, player.getUUID()).atLeast(PocketRole.BUILDER)) {
            player.sendSystemMessage(MinecraftMenuText.text(player, wall == null ? PocketsMessages.ROOM_BLOCKED : PocketsMessages.DENIED_BUILD, Map.of()));
            return CompletableFuture.completedFuture(null);
        }
        int maximum = configuration.settings().getPockets().roomsPerPocketMax;
        PocketRoom allocated = PocketRooms.allocate(space, wall, maximum).orElse(null);
        if (allocated == null) {
            player.sendSystemMessage(MinecraftMenuText.text(player, PocketsMessages.ROOM_LIMIT, Map.of("count", maximum)));
            return CompletableFuture.completedFuture(null);
        }
        UUID owner = player.getUUID();
        DoorPairIdentity pair = DoorPairIdentity.create();
        PlacedDoorEndpoint near = new PlacedDoorEndpoint(placed.position(), pair.endpoint(PairEndpoint.A));
        PocketLayout layout = PocketRooms.layout(space, allocated);
        PocketBlockPosition point = PocketRooms.matePosition(layout, wall);
        PlacedDoorEndpoint far = new PlacedDoorEndpoint(new DoorPosition(placed.position().worldId(), placed.position().worldKey(),
            point.x(), point.y(), point.z()), pair.endpoint(PairEndpoint.B));
        return locked(space, () -> {
            mutationBounds.put(space.spaceId(), layout);
            return rooms.prepareAdditional(layout).thenComposeAsync(room -> {
                try {
                    requireActive();
                    if (!placement.getAsBoolean()) {
                        room.close();
                        return CompletableFuture.completedFuture(null);
                    }
                    BlockPos lower = new BlockPos(point.x(), point.y(), point.z());
                    BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, MinecraftDoorService.facing(wall.reverse())).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.OPEN, false).setValue(DoorBlock.POWERED, false);
                    room.level().setBlock(lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS);
                    room.level().setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
                    return read(() -> registerRoom(space, allocated, pair, near, far, owner)).thenApplyAsync(updated -> {
                        doors.publishPocket(updated);
                        doors.publishEndpoint(near);
                        doors.publishEndpoint(far);
                        player.sendSystemMessage(MinecraftMenuText.text(player, PocketsMessages.ROOM_ADDED,
                            Map.of("count", allocated.index(), "space", space.spaceId())));
                        return updated;
                    }, server).whenCompleteAsync((updated, failure) -> {
                        if (failure != null) {
                            room.level().setBlock(lower, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                            room.level().setBlock(lower.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                        room.close();
                    }, server);
                } catch (RuntimeException exception) {
                    room.close();
                    return CompletableFuture.failedFuture(exception);
                }
            }, server);
        });
    }

    private PocketSpace registerRoom(PocketSpace source, PocketRoom allocated, DoorPairIdentity pair,
                                     PlacedDoorEndpoint near, PlacedDoorEndpoint far, UUID owner) throws IOException {
        try {
            PocketSpace active = current(source);
            if (!active.rooms().equals(source.rooms()) || !active.shell().equals(source.shell())) {
                throw new IllegalStateException("Pocket changed while adding a room");
            }
            state.registerPair(pair);
            state.registerEndpoint(near, owner);
            state.registerEndpoint(far, owner);
            List<PocketRoom> additions = new ArrayList<>(active.rooms());
            additions.add(new PocketRoom(allocated.index(), allocated.offsetX(), allocated.offsetZ(), near.identity().itemId(), far.identity().itemId()));
            return state.replacePocket(active.withRooms(additions));
        } catch (IOException | RuntimeException exception) {
            try {
                state.removeEndpoint(near.position());
                state.removeEndpoint(far.position());
                state.removeAccessRecord(near.identity().itemId());
                state.removeAccessRecord(far.identity().itemId());
                state.removePair(pair.pairId());
            } catch (IOException | RuntimeException rollback) {
                exception.addSuppressed(rollback);
            }
            throw exception;
        }
    }

    public CompletableFuture<PocketResizeOutcome> resize(PocketSpace space, PocketShell target, boolean confirmed) {
        runtime.requireServerThread();
        if (space.shell().equals(target)) {
            return CompletableFuture.completedFuture(PocketResizeOutcome.unchanged(space, target));
        }
        MinecraftPocketResize.validate(target);
        return locked(space, () -> {
            mutationBounds.put(space.spaceId(), new PocketLayout(space.withShell(space.shell().size() > target.size() ? space.shell() : target)));
            return rooms.load(space.withShell(space.shell().size() > target.size() ? space.shell() : target))
            .thenComposeAsync(room -> resizes.assess(room.level(), space, target).thenCompose(impact -> {
                PocketResizePolicy.Decision decision = PocketResizePolicy.decide(impact, confirmed);
                if (decision == PocketResizePolicy.Decision.NON_EMPTY_CONTAINERS) {
                    return CompletableFuture.completedFuture(PocketResizeOutcome.nonEmptyContainers(space, target, impact));
                }
                if (decision == PocketResizePolicy.Decision.NEEDS_CONFIRMATION) {
                    return CompletableFuture.completedFuture(PocketResizeOutcome.needsConfirmation(space, target, impact));
                }
                return read(() -> resizeWorkflow.execute(space, target, resizeActions(room)))
                    .thenCompose(stage -> stage.toCompletableFuture()).thenApplyAsync(updated -> {
                        doors.publishPocket(updated);
                        return PocketResizeOutcome.resized(updated, target, impact);
                    }, server);
            }).whenCompleteAsync((outcome, failure) -> room.close(), server), server);
        });
    }

    private PocketResizeWorkflow.Actions resizeActions(MinecraftPocketRooms.Prepared room) {
        return new PocketResizeWorkflow.Actions(
            (source, target) -> PocketResizeWorkflow.validateScheduledSource(source, current(source)),
            (source, target) -> CompletableFuture.completedFuture(null)
                .thenComposeAsync(ignored -> resizes.apply(room.level(), source, target), server),
            (source, target) -> state.reshapePocket(source.spaceId(), source.shell(), target),
            (source, updated) -> publishEndpoint(updated),
            (task, retired) -> {
                if (closed) {
                    return false;
                }
                storage.execute(task);
                return true;
            });
    }

    private void publishEndpoint(PocketSpace space) throws IOException {
        PlacedDoorEndpoint endpoint = MinecraftPocketRooms.endpoint(new PocketLayout(space));
        PlacedDoorEndpoint previous = state.findEndpointByItem(endpoint.identity().itemId()).orElse(null);
        if (previous == null) {
            state.registerEndpoint(endpoint);
        } else if (!previous.equals(endpoint)) {
            state.relocateEndpoint(previous, endpoint);
        }
    }

    public CompletableFuture<PocketSpace> apply(PocketSpace space, String name, boolean clear) {
        runtime.requireServerThread();
        return locked(space, () -> read(() -> templates.load(name).orElseThrow(() -> new IOException("Pocket template is missing: " + name)))
            .thenComposeAsync(structure -> begin(space, structure, name, clear), server));
    }

    public CompletableFuture<PocketSpace> restore(PocketSpace space, String name) {
        runtime.requireServerThread();
        return locked(space, () -> read(() -> snapshots.load(space.spaceId(), name)
                .orElseThrow(() -> new IOException("Pocket snapshot is missing: " + name)))
            .thenComposeAsync(structure -> begin(space, structure, space.templateName(), true), server));
    }

    public CompletableFuture<PocketSpace> snapshot(PocketSpace space, String name) {
        runtime.requireServerThread();
        return locked(space, () -> rooms.prepare(space).thenComposeAsync(room -> {
            StructureTemplate structure;
            try {
                requireActive();
                structure = templates.capture(room.level(), space);
            } catch (RuntimeException exception) {
                room.close();
                return CompletableFuture.failedFuture(exception);
            }
            return read(() -> {
                snapshots.save(space.spaceId(), name, structure);
                return space;
            }).whenCompleteAsync((saved, failure) -> room.close(), server);
        }, server));
    }

    public CompletableFuture<PocketSpace> rules(PocketSpace space, PocketRules rules) {
        runtime.requireServerThread();
        return read(() -> state.replacePocket(current(space).withRules(rules)));
    }

    @Override
    public void close() {
        closed = true;
        state.attachInstances(null);
        profiles.shutdownNow();
        busy.clear();
        mutationBounds.clear();
    }

    private CompletableFuture<PocketSpace> begin(PocketSpace space, StructureTemplate structure, String name, boolean clear) {
        requireActive();
        templates.validate(space, structure);
        return read(() -> {
            if (journal.pending(space.spaceId()).isPresent()) {
                throw new IllegalStateException("Pocket has an unfinished mutation requiring recovery");
            }
            io.save(structure, pendingFile(space.spaceId()));
            return clear ? journal.beginReset(space, name) : journal.beginPaste(space, name);
        }).thenComposeAsync(intent -> paste(space, structure, intent), server);
    }

    private CompletableFuture<PocketSpace> paste(PocketSpace space, StructureTemplate structure, PocketMutationIntent intent) {
        requireActive();
        return rooms.prepare(space).thenComposeAsync(room -> {
            CompletableFuture<Void> cleared = intent.kind() == PocketMutationIntent.Kind.RESET
                ? clear(room, 0L) : CompletableFuture.completedFuture(null);
            return cleared.thenComposeAsync(ignored -> {
                requireActive();
                templates.paste(room.level(), space, structure);
                return rooms.prepare(space);
            }, server).thenComposeAsync(repaired -> {
                repaired.close();
                return read(() -> {
                    PocketSpace updated = state.replacePocket(current(space).withTemplateName(intent.templateName()));
                    journal.complete(intent);
                    Files.deleteIfExists(pendingFile(space.spaceId()));
                    return updated;
                });
            }, server).whenCompleteAsync((updated, failure) -> room.close(), server);
        }, server);
    }

    private CompletableFuture<Void> clear(MinecraftPocketRooms.Prepared room, long start) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            requireActive();
            PocketTemplateService.Placement interior = PocketTemplateService.interior(room.layout());
            long count = (long) interior.sizeX() * interior.sizeY() * interior.sizeZ();
            long limit = Math.min(count, start + CLEAR_PER_TICK);
            for (long index = start; index < limit; index++) {
                int z = (int) (index % interior.sizeZ());
                int y = (int) ((index / interior.sizeZ()) % interior.sizeY());
                int x = (int) (index / ((long) interior.sizeZ() * interior.sizeY()));
                room.level().setBlock(new BlockPos(interior.originX() + x, interior.originY() + y, interior.originZ() + z),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            if (limit == count) {
                result.complete(null);
            } else if (!runtime.schedule(() -> clear(room, limit).whenComplete((ignored, error) -> {
                if (error == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(error);
                }
            }), 1L)) {
                result.completeExceptionally(new IllegalStateException("Pocket clear scheduler stopped"));
            }
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private void recover(List<PocketMutationIntent> interrupted) {
        for (PocketMutationIntent intent : interrupted) {
            PocketSpace space = state.findPocketById(intent.spaceId()).orElse(null);
            if (space == null) {
                LOGGER.error("Pending pocket mutation {} has no pocket {}", intent.operationId(), intent.spaceId());
                continue;
            }
            if (intent.kind() == PocketMutationIntent.Kind.RESIZE) {
                recoverResize(space, intent);
                continue;
            }
            locked(space, () -> read(() -> io.load(pendingFile(space.spaceId())).orElseThrow(() ->
                    new IOException("Pending pocket structure is missing for " + space.spaceId())))
                .thenComposeAsync(structure -> paste(space, structure, intent), server))
                .whenComplete((updated, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Could not recover pocket mutation {}", intent.operationId(), failure);
                    }
                });
        }
    }

    private void recoverResize(PocketSpace space, PocketMutationIntent intent) {
        PocketShell largest = intent.source().size() > intent.target().size() ? intent.source() : intent.target();
        mutationBounds.put(space.spaceId(), new PocketLayout(space.withShell(largest)));
        locked(space, () -> rooms.load(space.withShell(largest)).thenComposeAsync(room ->
            read(() -> resizeWorkflow.recover(intent, space, resizeActions(room))).thenCompose(stage -> stage.toCompletableFuture())
                .whenCompleteAsync((updated, failure) -> room.close(), server), server))
            .whenCompleteAsync((updated, failure) -> {
                if (failure != null) {
                    LOGGER.error("Could not recover pocket resize {}", intent.operationId(), failure);
                } else {
                    doors.publishPocket(updated);
                }
            }, server);
    }

    private <T> CompletableFuture<T> locked(PocketSpace space, Supplier<CompletableFuture<T>> action) {
        requireActive();
        if (!busy.add(space.spaceId())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pocket already has a mutation in progress"));
        }
        mutationBounds.putIfAbsent(space.spaceId(), new PocketLayout(space));
        CompletableFuture<T> operation;
        try {
            operation = action.get();
        } catch (RuntimeException exception) {
            busy.remove(space.spaceId());
            if (journal.pending(space.spaceId()).isEmpty()) {
                mutationBounds.remove(space.spaceId());
            }
            return CompletableFuture.failedFuture(exception);
        }
        return operation.whenCompleteAsync((updated, failure) -> {
            busy.remove(space.spaceId());
            if (journal.pending(space.spaceId()).isEmpty()) {
                mutationBounds.remove(space.spaceId());
            }
        }, server);
    }

    private <T> CompletableFuture<T> read(IoOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            requireActive();
            try {
                return operation.run();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, storage);
    }

    private PocketSpace current(PocketSpace space) {
        return state.findPocketById(space.spaceId()).orElseThrow(() -> new IllegalStateException("Pocket no longer exists"));
    }

    private Path pendingFile(UUID spaceId) {
        return pendingStructures.resolve(spaceId + ".nbt");
    }

    private void requireActive() {
        if (closed) {
            throw new IllegalStateException("Pocket operations stopped");
        }
    }

    private PocketSpace standing(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        PocketSpace space = player == null ? null : doors.spaceAt(player.level(), player.blockPosition());
        if (space == null) {
            throw new IllegalArgumentException(runtime.localization().text(source.getPlayer(), WormholesMessages.COMMAND_POCKET_NOT_INSIDE, Map.of()).getString());
        }
        return space;
    }

    private int info(CommandSourceStack source) {
        PocketSpace space = standing(source);
        PocketLayout layout = new PocketLayout(space);
        lines(source, WormholesMessages.COMMAND_POCKET_INFO, Map.of("space", space.spaceId(), "size", layout.size(),
            "material", space.shell().shellMaterial(), "door", space.shell().returnDoorMaterial(),
            "minimum", layout.minX() + ", " + layout.minY() + ", " + layout.minZ(),
            "maximum", layout.maxX() + ", " + layout.maxY() + ", " + layout.maxZ()));
        return 1;
    }

    private int list(CommandSourceStack source) {
        read(templates::names).whenCompleteAsync((names, failure) -> {
            if (failure != null) {
                reportFailure(source, failure);
            } else {
                send(source, PocketsMessages.TEMPLATE_LIST, Map.of("count", names.size(), "value", names.isEmpty() ? "-" : String.join(", ", names)));
            }
        }, server);
        return 1;
    }

    private int resizeAll(CommandSourceStack source, ResizeRequest request) {
        List<PocketSpace> spaces = state.spaces();
        send(source, WormholesMessages.COMMAND_POCKET_BULK_STARTED, Map.of("count", spaces.size()));
        int[] totals = new int[3];
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (PocketSpace space : spaces) {
            chain = chain.thenComposeAsync(ignored -> resize(current(space), request.target(current(space).shell()), request.confirm())
                .handle((outcome, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Could not resize pocket {}", space.spaceId(), failure);
                        totals[2]++;
                    } else if (outcome.succeeded()) {
                        totals[0]++;
                    } else {
                        totals[1]++;
                    }
                    return null;
                }), server);
        }
        chain.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                reportFailure(source, failure);
            }
            send(source, WormholesMessages.COMMAND_POCKET_BULK_FINISHED,
                Map.of("resized", totals[0], "skipped", totals[1], "failed", totals[2]));
        }, server);
        return 1;
    }

    private int resizeCommand(CommandSourceStack source, PocketShell shell, boolean confirm) {
        PocketSpace previous = standing(source);
        resize(previous, shell, confirm).whenCompleteAsync((outcome, failure) -> {
            if (failure != null) {
                reportFailure(source, failure);
                return;
            }
            switch (outcome.status()) {
                case RESIZED -> send(source, WormholesMessages.COMMAND_POCKET_RESIZED,
                    Map.of("size", shell.size(), "previous", previous.shell().size(), "material", shell.shellMaterial(), "door", shell.returnDoorMaterial()));
                case NEEDS_CONFIRMATION -> lines(source, WormholesMessages.COMMAND_POCKET_CONFIRM_REQUIRED,
                    Map.of("size", shell.size(), "blocks", outcome.impact().blocks(), "entities", outcome.impact().entities()));
                case NON_EMPTY_CONTAINERS -> send(source, WormholesMessages.COMMAND_POCKET_CONTAINERS_NOT_EMPTY, Map.of("containers", outcome.impact().containers()));
                case UNCHANGED -> send(source, WormholesMessages.COMMAND_POCKET_UNCHANGED, Map.of());
                case WORLD_UNAVAILABLE -> send(source, WormholesMessages.COMMAND_POCKET_WORLD_UNAVAILABLE, Map.of());
                case DOES_NOT_FIT -> send(source, WormholesMessages.COMMAND_POCKET_DOES_NOT_FIT, Map.of("size", shell.size()));
                case FAILED -> send(source, WormholesMessages.COMMAND_POCKET_FAILED, Map.of());
            }
        }, server);
        return 1;
    }

    private int applyCommand(CommandSourceStack source, String name, boolean confirm) {
        if (!confirm) {
            lines(source, PocketsMessages.CONFIRM_OVERWRITE, Map.of("space", standing(source).spaceId()));
            return 0;
        }
        PocketSpace space = standing(source);
        return report(source, apply(space, name, true), new Feedback(PocketsMessages.TEMPLATE_APPLIED, Map.of("name", name, "space", space.spaceId())));
    }

    private int snapshotCommand(CommandSourceStack source, String name) {
        PocketSpace space = standing(source);
        return report(source, snapshot(space, name), new Feedback(PocketsMessages.SNAPSHOT_SAVED, Map.of("name", name, "space", space.spaceId())));
    }

    private int restoreCommand(CommandSourceStack source, String name, boolean confirm) {
        if (!confirm) {
            lines(source, PocketsMessages.CONFIRM_OVERWRITE, Map.of("space", standing(source).spaceId()));
            return 0;
        }
        PocketSpace space = standing(source);
        return report(source, restore(space, name), new Feedback(PocketsMessages.SNAPSHOT_RESTORED, Map.of("name", name, "space", space.spaceId())));
    }

    private int rule(CommandSourceStack source, String name, boolean enabled) {
        PocketSpace space = standing(source);
        PocketRules updated = switch (name) {
            case "mobs" -> space.rules().withMobs(enabled);
            case "pvp" -> space.rules().withPvp(enabled);
            case "keep-inventory" -> space.rules().withKeepInventory(enabled);
            default -> throw new IllegalArgumentException("Unknown pocket rule " + name);
        };
        return report(source, rules(space, updated), new Feedback(PocketsMessages.RULES_SET, Map.of("key", name, "value", enabled)));
    }

    private int time(CommandSourceStack source, int ticks) {
        PocketSpace space = standing(source);
        return report(source, rules(space, space.rules().withFixedTime(ticks)), new Feedback(PocketsMessages.RULES_SET, Map.of("key", "fixed-time", "value", ticks)));
    }

    private int build(CommandSourceStack source, String policy) {
        PocketSpace space = standing(source);
        PocketRules.BuildPolicy parsed;
        try {
            parsed = PocketRules.BuildPolicy.parse(policy);
        } catch (IllegalArgumentException failure) {
            send(source, PocketsMessages.RULES_INVALID, Map.of("key", "build"));
            return 0;
        }
        return report(source, rules(space, space.rules().withBuild(parsed)), new Feedback(PocketsMessages.RULES_SET, Map.of("key", "build", "value", parsed.configValue())));
    }

    private int assign(CommandSourceStack source, String name, String role, TextKey message) {
        PocketSpace space = standing(source);
        PocketRole parsed;
        try {
            parsed = PocketRole.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            send(source, PocketsMessages.RULES_INVALID, Map.of("key", role));
            return 0;
        }
        resolvePlayer(name).thenCompose(id -> id.map(player -> read(() -> {
            roster.assign(space.spaceId(), player, parsed);
            return true;
        })).orElseGet(() -> CompletableFuture.completedFuture(false))).whenCompleteAsync((found, failure) -> {
            if (failure != null) {
                reportFailure(source, failure);
            } else {
                send(source, found ? message : PocketsMessages.ROSTER_MISSING, Map.of("name", name, "value", parsed.name().toLowerCase(Locale.ROOT)));
            }
        }, server);
        return 1;
    }

    private int remove(CommandSourceStack source, String name) {
        PocketSpace space = standing(source);
        resolvePlayer(name).thenCompose(id -> id.map(player -> read(() -> roster.remove(space.spaceId(), player)))
            .orElseGet(() -> CompletableFuture.completedFuture(false))).whenCompleteAsync((removed, failure) -> {
                if (failure != null) {
                    reportFailure(source, failure);
                } else {
                    send(source, removed ? PocketsMessages.ROSTER_REMOVED : PocketsMessages.ROSTER_MISSING, Map.of("name", name));
                }
            }, server);
        return 1;
    }

    private CompletableFuture<Optional<UUID>> resolvePlayer(String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUUID()));
        }
        return CompletableFuture.supplyAsync(() -> server.services().nameToIdCache().get(name).map(profile -> profile.id()), profiles)
            .orTimeout(10, TimeUnit.SECONDS);
    }

    private int report(CommandSourceStack source, CompletableFuture<?> operation, Feedback success) {
        operation.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                reportFailure(source, failure);
            } else {
                send(source, success.message(), success.arguments());
            }
        }, server);
        return 1;
    }

    private void reportFailure(CommandSourceStack source, Throwable failure) {
        LOGGER.error("Pocket operation failed", failure);
        source.sendFailure(runtime.localization().text(source.getPlayer(), WormholesMessages.COMMAND_POCKET_FAILED, Map.of()));
    }

    private void send(CommandSourceStack source, TextKey key, Map<String, ?> arguments) {
        source.sendSuccess(() -> runtime.localization().text(source.getPlayer(), key, arguments), false);
    }

    private void lines(CommandSourceStack source, LinesKey key, Map<String, ?> arguments) {
        for (Component line : MinecraftMenuText.lines(runtime.localization().snapshot(source.getPlayer()), key, arguments)) {
            source.sendSuccess(() -> line, false);
        }
    }

    private record ResizeRequest(int size, String material, String door, boolean confirm) {
        private PocketShell target(PocketShell previous) {
            return new PocketShell(size == 0 ? previous.size() : size,
                material.equalsIgnoreCase("keep") ? previous.shellMaterial() : material,
                door.equalsIgnoreCase("keep") ? previous.returnDoorMaterial() : door);
        }
    }

    private record ResizeCommand(Supplier<MinecraftPocketService> service, boolean all) {
        private int run(CommandContext<CommandSourceStack> context, int supplied) {
            ResizeRequest request = new ResizeRequest(
                supplied >= 1 ? IntegerArgumentType.getInteger(context, "size") : 0,
                supplied >= 2 ? StringArgumentType.getString(context, "material") : "keep",
                supplied >= 3 ? StringArgumentType.getString(context, "door") : "keep",
                supplied >= 4 && BoolArgumentType.getBool(context, "confirm"));
            MinecraftPocketService active = service.get();
            if (request.size() != 0 && !PocketShell.isSupportedSize(request.size())) {
                active.send(context.getSource(), WormholesMessages.COMMAND_POCKET_INVALID_SIZE,
                    Map.of("minimum", PocketShell.MIN_SIZE, "maximum", PocketShell.MAX_SIZE));
                return 0;
            }
            return all ? active.resizeAll(context.getSource(), request)
                : active.resizeCommand(context.getSource(), request.target(active.standing(context.getSource()).shell()), request.confirm());
        }
    }

    private record Feedback(TextKey message, Map<String, ?> arguments) {
    }

    record Options(Path directory, MinecraftDoorService doors, MinecraftPocketRooms rooms, Executor storage) {
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
