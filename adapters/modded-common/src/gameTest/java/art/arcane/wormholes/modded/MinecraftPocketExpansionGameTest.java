package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorPairIdentity;
import art.arcane.wormholes.door.DoorTransitGate;
import art.arcane.wormholes.door.DoorVec3;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.PairEndpoint;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.PocketBinding;
import art.arcane.wormholes.door.PocketDoorDestination;
import art.arcane.wormholes.door.PocketInstanceInfo;
import art.arcane.wormholes.door.PocketInstances;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketRoom;
import art.arcane.wormholes.door.PocketRooms;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.wormholes.door.DoorItemIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

public final class MinecraftPocketExpansionGameTest {
    private final Options options;
    private final MinecraftDoorService doors;
    private final MinecraftPocketService service;
    private final DoorStateService state;
    private final ServerLevel level;
    private final PocketLayout base;
    private final BlockPos near;
    private final Vec3 originalPosition;
    private Mob traveler;
    private boolean forcedChunk;
    private String assertion = "room placement";

    private MinecraftPocketExpansionGameTest(Options options) {
        this.options = options;
        doors = options.runtime().doors();
        service = doors.pocketOperations();
        state = doors.state();
        level = options.player().level();
        base = new PocketLayout(options.pocket());
        near = new BlockPos(base.minX() + 4, base.minY() + 1, base.minZ() + 1);
        originalPosition = options.player().position();
    }

    public static CompletableFuture<Boolean> verify(Options options) {
        return new MinecraftPocketExpansionGameTest(options).run();
    }

    private CompletableFuture<Boolean> run() {
        DoorPairIdentity kit = DoorPairIdentity.create();
        return CompletableFuture.runAsync(() -> {
            try {
                state.registerPair(kit);
                state.replacePocket(current().withRules(current().rules().withMobs(true)));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).thenComposeAsync(ignored -> {
            ServerPlayer player = options.player();
            player.setPos(near.getX() + 0.5, near.getY(), near.getZ() + 2.5);
            player.setYRot(180);
            player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftDoorItems.door(kit.endpoint(PairEndpoint.A)));
            BlockPos floor = near.below();
            options.helper().assertTrue(options.runtime().useBlock(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)), "Pocket room placement was not consumed");
            return until(() -> current().rooms().size() == options.pocket().rooms().size() + 1 && !service.busy(options.pocket().spaceId()), 300);
        }, level.getServer()).thenComposeAsync(ignored -> {
            PocketSpace expanded = current();
            PocketRoom room = expanded.rooms().getLast();
            PlacedDoorEndpoint source = state.findEndpointByItem(room.doorItemId()).orElseThrow();
            PlacedDoorEndpoint target = state.findEndpointByItem(room.linkedDoorItemId()).orElseThrow();
            options.helper().assertTrue(!source.identity().pairId().equals(kit.pairId()), "Room reused the carried kit's far half");
            options.helper().assertTrue(state.findMate(source.identity()).filter(target::equals).isPresent(), "Room pair was not durably linked");
            options.helper().assertTrue(doors.spaceAt(level, new BlockPos(target.position().x(), target.position().y(), target.position().z())) != null,
                "Additional room was not indexed");
            open(near);
            open(new BlockPos(target.position().x(), target.position().y(), target.position().z()));
            forcedChunk = level.setChunkForced(near.getX() >> 4, near.getZ() >> 4, true);
            traveler = EntityTypes.CHICKEN.create(level, EntitySpawnReason.COMMAND);
            options.helper().assertTrue(traveler != null, "Could not create room traveler");
            traveler.setNoGravity(true);
            traveler.snapTo(near.getX() + 0.5, near.getY(), near.getZ() + 1.5);
            options.helper().assertTrue(level.addFreshEntity(traveler), "Room traveler spawn was rejected");
            assertion = "room entity-ticking chunk";
            options.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            options.player().setPos(originalPosition);
            return until(() -> level.areEntitiesActuallyLoadedAndTicking(new ChunkPos(near.getX() >> 4, near.getZ() >> 4)), 120).thenComposeAsync(nothing -> {
                assertion = "room entity traversal";
                options.helper().assertTrue(Math.abs(traveler.yo - near.getY()) < 0.01, "Room traveler previous position was not initialized");
                MinecraftDoorService.DoorView view = doors.projectableViews().stream()
                    .filter(candidate -> candidate.endpoint().equals(source)).findFirst().orElseThrow();
                options.helper().assertTrue(view.active(), "Room source is not active");
                options.helper().assertTrue(DoorTransitGate.detect(view.plane(),
                    new DoorVec3(traveler.getX(), traveler.getY(), traveler.getZ()),
                    new DoorVec3(near.getX() + 0.5, near.getY(), near.getZ() + 0.5), traveler.getBbWidth() * 0.5, traveler.getBbHeight()).isPresent(),
                    "Room movement does not cross its aperture");
                traveler.setDeltaMovement(0, 0, -0.35);
                PocketLayout destination = PocketRooms.layout(expanded, room);
                return until(() -> destination.contains(traveler.blockPosition().getX(), traveler.blockPosition().getY(), traveler.blockPosition().getZ()), 120);
            }, level.getServer());
        }, level.getServer()).thenComposeAsync(ignored -> {
            PocketRoom room = current().rooms().getLast();
            level.setBlock(near, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(near.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            assertion = "missing endpoint reconciliation";
            return until(() -> state.findEndpointByItem(room.doorItemId()).isEmpty(), 120);
        }, level.getServer()).thenComposeAsync(ignored -> service.rules(current(), options.pocket().rules()), level.getServer())
            .thenComposeAsync(ignored -> instance(), level.getServer()).thenApplyAsync(ignored -> {
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS pocket_expansion wall_placement fresh_internal_pair room_index entity_traversal stale_endpoint_cleanup instance_binding reset_template");
            return true;
        }, level.getServer()).whenCompleteAsync((result, failure) -> {
            if (traveler != null) {
                traveler.discard();
            }
            if (forcedChunk) {
                level.setChunkForced(near.getX() >> 4, near.getZ() >> 4, false);
            }
            options.player().setPos(originalPosition);
            options.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }, level.getServer());
    }

    private CompletableFuture<PocketSpace> instance() {
        DoorItemIdentity publicDoor = DoorItemIdentity.newPublic();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.writeString(service.templates().file("runtime-template").resolveSibling("runtime-template.toml"), "instanced = true\n");
                state.replacePocket(state.getOrAllocatePocket(PocketBinding.publicDoor(publicDoor.itemId()), options.pocket().shell()).withTemplateName("runtime-template"));
                PocketDoorDestination one = service.destination(publicDoor, first);
                PocketDoorDestination two = service.destination(publicDoor, second);
                options.helper().assertTrue(one.isInstanced() && two.isInstanced() && !one.binding().equals(two.binding()), "Public template did not isolate travelers");
                options.helper().assertTrue(one.binding().equals(service.destination(publicDoor, first).binding()), "Instance binding changed on repeat entry");
                PocketInstanceInfo info = PocketInstances.newInstance("runtime-template", first, PocketInstances.RESET_ON_EMPTY,
                    System.currentTimeMillis() - 60_000L).withLastOccupied(System.currentTimeMillis() - 30_000L);
                return state.replacePocket(state.getOrAllocatePocket(one.binding(), options.pocket().shell()).withTemplateName("runtime-template").withInstance(info));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }).thenComposeAsync(space -> service.apply(space, "runtime-template", true), level.getServer()).thenComposeAsync(space -> {
            PocketLayout layout = new PocketLayout(space);
            BlockPos altered = new BlockPos(layout.minX() + 3, layout.minY() + 1, layout.minZ() + 3);
            level.setBlock(altered, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            assertion = "idle instance template reset";
            service.sweepInstances(System.currentTimeMillis());
            return until(() -> !service.busy(space.spaceId()) && level.getBlockState(altered).isAir()
                && state.findPocketById(space.spaceId()).orElseThrow().instance().lastResetMillis() > space.instance().lastResetMillis(), 300)
                .thenApply(ignored -> state.findPocketById(space.spaceId()).orElseThrow());
        }, level.getServer());
    }

    private PocketSpace current() {
        return state.findPocketById(options.pocket().spaceId()).orElseThrow();
    }

    private void open(BlockPos block) {
        BlockState value = level.getBlockState(block);
        options.helper().assertTrue(value.getBlock() instanceof DoorBlock, "Room endpoint has no vanilla door");
        ((DoorBlock) value.getBlock()).setOpen(options.player(), level, value, block, true);
    }

    private CompletableFuture<Boolean> until(BooleanSupplier condition, int remaining) {
        try {
            if (condition.getAsBoolean()) {
                return CompletableFuture.completedFuture(true);
            }
            if (remaining <= 0) {
                return CompletableFuture.failedFuture(new AssertionError("Pocket expansion timed out: " + assertion + (traveler == null ? "" : " at " + traveler.position() + " previous=" + new Vec3(traveler.xo, traveler.yo, traveler.zo)
                    + " velocity=" + traveler.getDeltaMovement() + " alive=" + traveler.isAlive() + " travelling=" + doors.travelling(traveler.getUUID()))));
            }
            return delay(1).thenCompose(ignored -> until(condition, remaining - 1));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<Boolean> delay(long ticks) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        options.runtime().schedule(() -> result.complete(true), ticks);
        return result;
    }

    public record Options(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer player, PocketSpace pocket) {
    }
}
