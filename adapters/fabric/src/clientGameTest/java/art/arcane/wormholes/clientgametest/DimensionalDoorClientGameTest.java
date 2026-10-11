package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.door.DoorAccessPolicy;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.modded.MinecraftDoorItems;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.TravelMessage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class DimensionalDoorClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos SOURCE = new BlockPos(32, 100, 32);
    private static final BlockPos DESTINATION = new BlockPos(96, 100, 32);
    private static final int PREPARE_TICKS = 600;
    private static final int CROSSING_TICKS = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        paired(context, Level.OVERWORLD, false);
        paired(context, Level.NETHER, false);
        paired(context, Level.OVERWORLD, true);
    }

    private static void paired(ClientGameTestContext context, ResourceKey<Level> destinationLevel, boolean blocked) {
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            SeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            List<ItemStack> items = unpack(context, world);
            DoorItemIdentity source = place(context, world, Level.OVERWORLD, SOURCE, items.getFirst());
            DoorItemIdentity destination = place(context, world, destinationLevel, DESTINATION, items.get(1));
            world.getServer().runOnServer(server -> {
                ServerPlayer player = world.getConnection().getServerPlayer();
                ServerLevel level = Objects.requireNonNull(server.getLevel(destinationLevel));
                assertTrue(!level.getBlockState(DESTINATION).getValue(DoorBlock.OPEN), "paired destination was already open before source interaction");
                if (blocked) {
                    obstruct(level);
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.containerMenu.broadcastFullState();
                player.teleportTo(server.overworld(), SOURCE.getX() + 0.5D, SOURCE.getY(), SOURCE.getZ() - 2.0D,
                    Set.of(), 0.0F, 0.0F, false);
            });
            client.waitFor(minecraft -> minecraft.level.dimension().equals(Level.OVERWORLD)
                && minecraft.player.position().distanceTo(start(SOURCE)) < 0.5D, PREPARE_TICKS);
            client.waitForChunksDownload();
            open(context, SOURCE);
            prepare(client, source.itemId(), destinationLevel);
            if (blocked) {
                blocked(client, world, destinationLevel);
                return;
            }
            cross(client, world, destinationLevel, DESTINATION, "paired door outbound");
            assertTrue(world.getServer().computeOnServer(server -> Objects.requireNonNull(server.getLevel(destinationLevel))
                .getBlockState(DESTINATION).getValue(DoorBlock.OPEN)), "successful arrival left the destination door closed");
            client.holdForwardFor(10);
            client.waitTicks(30);
            Vec3 departed = client.computeOnClient(minecraft -> minecraft.player.position());
            assertTrue(departed.distanceTo(Vec3.atBottomCenterOf(DESTINATION)) > 1.2D, "player did not leave the destination doorway before returning");
            client.lookAt((float) Math.toDegrees(Math.atan2(-(DESTINATION.getX() + 0.5D - departed.x),
                DESTINATION.getZ() + 0.5D - departed.z)), 0.0F);
            if (!client.computeOnClient(minecraft -> minecraft.level.getBlockState(DESTINATION).getValue(DoorBlock.OPEN))) {
                open(context, DESTINATION);
            }
            prepare(client, destination.itemId(), Level.OVERWORLD);
            cross(client, world, Level.OVERWORLD, SOURCE, "paired door return");
            LOGGER.info("WORMHOLES_DIMENSIONAL_DOOR_PASS {} walking_roundtrip closed_mate native_collision no_respawn no_correction",
                destinationLevel.identifier());
        }
    }

    private static List<ItemStack> unpack(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            assertAdministrator(server, player);
            LOGGER.info("WORMHOLES_DOOR_PERMISSION_READY {}", permissionDetails(server, player));
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftDoorItems.pairKit(DoorForm.DOOR));
            assertTrue(runtime(server).useItem(player, InteractionHand.MAIN_HAND), "paired door kit did not unpack");
        });
        waitForServer(context, world, server -> tagged(world.getConnection().getServerPlayer()).size() == 2, "kit did not supply two paired door items");
        return world.getServer().computeOnServer(server -> tagged(world.getConnection().getServerPlayer()));
    }

    private static void assertAdministrator(MinecraftServer server, ServerPlayer player) {
        String details = permissionDetails(server, player);
        assertTrue(server.isSingleplayer() && server.isSingleplayerOwner(player.nameAndId()),
            "door fixture player is not the integrated server owner: " + details);
        assertTrue(server.getWorldData().isAllowCommands(), "door fixture world does not allow commands: " + details);
        assertTrue(Commands.hasPermission(Commands.LEVEL_ADMINS).test(player.createCommandSourceStack()),
            "door fixture owner lacks native administrator permission: " + details);
        assertTrue(runtime(server).access().permission(player, DoorAccessPolicy.PLACE_NODE),
            "door fixture owner cannot place dimensional doors: " + details);
    }

    private static String permissionDetails(MinecraftServer server, ServerPlayer player) {
        CommandSourceStack source = player.createCommandSourceStack();
        ServerOpListEntry entry = server.getPlayerList().getOps().get(player.nameAndId());
        return "singleplayer=" + server.isSingleplayer() + " owner=" + server.isSingleplayerOwner(player.nameAndId())
            + " commands=" + server.getWorldData().isAllowCommands() + " op=" + server.getPlayerList().isOp(player.nameAndId())
            + " storedOpLevel=" + (entry == null ? null : entry.permissions().level())
            + " defaultOpLevel=" + server.operatorUserPermissions().level()
            + " profileLevel=" + server.getProfilePermissions(player.nameAndId()).level()
            + " playerPermissions=" + player.permissions() + " sourcePermissions=" + source.permissions()
            + " nativeAdmin=" + Commands.hasPermission(Commands.LEVEL_ADMINS).test(source)
            + " placePermission=" + runtime(server).access().permission(source, DoorAccessPolicy.PLACE_NODE);
    }

    private static List<ItemStack> tagged(ServerPlayer player) {
        List<ItemStack> items = new ArrayList<>(2);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (MinecraftDoorItems.identity(item).isPresent()) {
                items.add(item.copy());
            }
        }
        return items;
    }

    private static DoorItemIdentity place(ClientGameTestContext context, TestSingleplayerContext world, ResourceKey<Level> dimension,
                                          BlockPos block, ItemStack item) {
        DoorItemIdentity identity = MinecraftDoorItems.identity(item).orElseThrow();
        world.getServer().runOnServer(server -> {
            ServerLevel level = Objects.requireNonNull(server.getLevel(dimension));
            preparePlacement(level, block);
            ServerPlayer player = world.getConnection().getServerPlayer();
            assertTrue(player.teleportTo(level, block.getX() + 0.5D, block.getY(), block.getZ() - 2.0D,
                Set.of(), 0.0F, 0.0F, false), "door fixture teleport failed");
        });
        context.waitFor(client -> client.level.dimension().equals(dimension)
            && client.player.position().distanceTo(start(block)) < 0.5D, PREPARE_TICKS);
        world.getConnection().waitForChunksDownload();
        world.getServer().runOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            player.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
            player.getInventory().setChanged();
            player.containerMenu.broadcastFullState();
        });
        context.waitFor(client -> MinecraftDoorItems.identity(client.player.getItemInHand(InteractionHand.MAIN_HAND))
            .filter(identity::equals).isPresent(), PREPARE_TICKS);
        int selectedSlot = context.computeOnClient(client -> client.player.getInventory().getSelectedSlot());
        world.getServer().runOnServer(server -> {
            ServerLevel level = Objects.requireNonNull(server.getLevel(dimension));
            ServerPlayer player = world.getConnection().getServerPlayer();
            assertTrue(player.getInventory().getSelectedSlot() == selectedSlot, "door fixture client/server selected slots differ");
            assertPlacementReady(server, player, level, block, identity);
            LOGGER.info("WORMHOLES_DOOR_PLACEMENT_READY {}", placementDetails(server, player, level, block, item));
            assertTrue(runtime(server).useBlock(player, InteractionHand.MAIN_HAND, placementHit(block)),
                "tagged door placement was not consumed");
        });
        waitForPlacement(context, world, dimension, block, item, identity);
        return identity;
    }

    private static void preparePlacement(ServerLevel level, BlockPos block) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -6; z <= 6; z++) {
                BlockPos support = block.offset(x, -1, z);
                level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
                assertTrue(level.getBlockState(support).is(Blocks.STONE), "door fixture foundation was not written at " + support);
                for (int y = 0; y < 4; y++) {
                    BlockPos air = block.offset(x, y, z);
                    level.setBlockAndUpdate(air, Blocks.AIR.defaultBlockState());
                    assertTrue(level.getBlockState(air).isAir(), "door fixture clearance was not written at " + air);
                }
            }
        }
    }

    private static BlockHitResult placementHit(BlockPos block) {
        BlockPos support = block.below();
        return new BlockHitResult(Vec3.atCenterOf(support).add(0.0D, 0.5D, 0.0D), Direction.UP, support, false);
    }

    private static void assertPlacementReady(MinecraftServer server, ServerPlayer player, ServerLevel level, BlockPos block,
                                             DoorItemIdentity identity) {
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        String details = placementDetails(server, player, level, block, held);
        assertTrue(player.level() == level && player.position().distanceTo(start(block)) < 0.5D,
            "door fixture player is not at the placement start: " + details);
        assertTrue(player.isAlive() && !player.hasDisconnected(), "door fixture player is unavailable: " + details);
        assertTrue(held.is(Items.OAK_DOOR) && MinecraftDoorItems.identity(held).filter(identity::equals).isPresent(),
            "door fixture hand does not contain the expected oak door: " + details);
        assertTrue(runtime(server).doors().enabled() && runtime(server).access().permission(player, DoorAccessPolicy.PLACE_NODE),
            "door fixture placement is disabled or unauthorized: " + details);
        assertTrue(runtime(server).doors().state().findPair(identity.pairId()).isPresent(),
            "door fixture pair was not registered: " + details);
        assertTrue(level.hasChunk(block.getX() >> 4, block.getZ() >> 4), "door fixture chunk is not loaded: " + details);
        assertTrue(level.getBlockState(block.below()).is(Blocks.STONE)
            && level.getBlockState(block.below()).isFaceSturdy(level, block.below(), Direction.UP),
            "door fixture support is not a sturdy stone foundation: " + details);
        BlockPlaceContext placement = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, held, placementHit(block));
        assertTrue(placement.getClickedPos().equals(block) && placement.canPlace()
            && level.getBlockState(block).isAir() && level.getBlockState(block.above()).isAir(),
            "door fixture placement cells are not clear: " + details);
        BlockState predicted = ((BlockItem) held.getItem()).getBlock().getStateForPlacement(placement);
        assertTrue(predicted != null && predicted.canSurvive(level, block), "door fixture cannot support a vanilla door: " + details);
        assertTrue(level.isUnobstructed(predicted, block, CollisionContext.placementContext(player)),
            "door fixture vanilla placement collides with an entity: " + details);
    }

    private static void waitForPlacement(ClientGameTestContext context, TestSingleplayerContext world, ResourceKey<Level> dimension,
                                         BlockPos block, ItemStack item, DoorItemIdentity identity) {
        for (int tick = 0; tick < PREPARE_TICKS; tick++) {
            if (world.getServer().computeOnServer(server -> {
                ServerLevel level = Objects.requireNonNull(server.getLevel(dimension));
                return runtime(server).doors().state().findEndpointByItem(identity.itemId()).isPresent()
                    && level.getBlockState(block).is(Blocks.OAK_DOOR)
                    && level.getBlockState(block).getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && level.getBlockState(block.above()).is(Blocks.OAK_DOOR)
                    && level.getBlockState(block.above()).getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
            })) {
                return;
            }
            if (tick == 0 || tick == 20 || tick == 100) {
                String details = world.getServer().computeOnServer(server -> placementDetails(server,
                    world.getConnection().getServerPlayer(), Objects.requireNonNull(server.getLevel(dimension)), block, item));
                LOGGER.info("WORMHOLES_DOOR_PLACEMENT_WAIT tick {} {}", tick, details);
            }
            context.waitTicks(1);
        }
        String details = world.getServer().computeOnServer(server -> placementDetails(server,
            world.getConnection().getServerPlayer(), Objects.requireNonNull(server.getLevel(dimension)), block, item));
        throw new AssertionError("tagged door was not placed at " + block + ": " + details);
    }

    private static String placementDetails(MinecraftServer server, ServerPlayer player, ServerLevel level, BlockPos block, ItemStack item) {
        DoorItemIdentity expected = MinecraftDoorItems.identity(item).orElse(null);
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        BlockPlaceContext placement = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, item, placementHit(block));
        BlockState predicted = item.getItem() instanceof BlockItem blockItem ? blockItem.getBlock().getStateForPlacement(placement) : null;
        return "dimension=" + level.dimension().identifier() + " block=" + block + " chunk=" + level.hasChunk(block.getX() >> 4, block.getZ() >> 4)
            + " support=" + level.getBlockState(block.below()) + " lower=" + level.getBlockState(block) + " upper=" + level.getBlockState(block.above())
            + " expected=" + expected + " endpoint=" + (expected == null ? null : runtime(server).doors().state().findEndpointByItem(expected.itemId()).orElse(null))
            + " pair=" + (expected != null && runtime(server).doors().state().findPair(expected.pairId()).isPresent())
            + " enabled=" + runtime(server).doors().enabled() + " permission=" + runtime(server).access().permission(player, DoorAccessPolicy.PLACE_NODE)
            + " permissions={" + permissionDetails(server, player) + "}"
            + " playerDimension=" + player.level().dimension().identifier() + " position=" + player.position() + " box=" + player.getBoundingBox()
            + " alive=" + player.isAlive() + " disconnected=" + player.hasDisconnected() + " selected=" + player.getInventory().getSelectedSlot()
            + " held=" + held + " heldIdentity=" + MinecraftDoorItems.identity(held).orElse(null) + " mayBuild=" + player.getAbilities().mayBuild
            + " clicked=" + placement.getClickedPos() + " replaceable=" + placement.canPlace() + " predicted=" + predicted
            + " survives=" + (predicted != null && predicted.canSurvive(level, placement.getClickedPos()))
            + " unobstructed=" + (predicted != null && level.isUnobstructed(predicted, placement.getClickedPos(), CollisionContext.placementContext(player)));
    }

    private static void open(ClientGameTestContext context, BlockPos block) {
        context.runOnClient(client -> {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(block), Direction.NORTH, block, false);
            assertTrue(client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit).consumesAction(), "client did not interact with the dimensional door");
        });
        context.waitFor(client -> client.level.getBlockState(block).is(Blocks.OAK_DOOR)
            && client.level.getBlockState(block).getValue(DoorBlock.OPEN), PREPARE_TICKS);
    }

    private static void prepare(SeamlessClient client, UUID source, ResourceKey<Level> destination) {
        client.waitFor(minecraft -> {
            ClientSeamlessTravel travel = WormholesClient.instance().seamlessTravel();
            if (!travel.armed(source) || travel.pending() || travel.unprepared() != null) {
                return false;
            }
            for (TravelMessage.TravelBegin arm : travel.arms()) {
                if (arm.sourcePortal().equals(source) && arm.resident()
                    && arm.world().dimension().equals(destination.identifier().toString())) {
                    ClientLevel level = travel.residents().level(arm.levelHandle());
                    int x = (int) Math.floor(arm.arrival().x()) >> 4;
                    int z = (int) Math.floor(arm.arrival().z()) >> 4;
                    return level != null && level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
                }
            }
            return false;
        }, PREPARE_TICKS);
        client.waitTicks(5);
    }

    private static void cross(SeamlessClient client, TestSingleplayerContext world, ResourceKey<Level> destination,
                              BlockPos door, String label) {
        int identity = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        client.runOnClient(minecraft -> TravelTap.reset());
        client.holdForward();
        try {
            client.waitFor(minecraft -> arrived(minecraft, destination, door), CROSSING_TICKS);
        } finally {
            client.releaseForward();
        }
        client.waitTicks(10);
        assertTrue(world.getServer().computeOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            return player.level().dimension().equals(destination) && player.position().distanceTo(Vec3.atBottomCenterOf(door)) < 3.0D;
        }), label + ": server did not reach the paired destination");
        assertTrue(client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player)) == identity, label + ": client player was replaced");
        assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
            label + ": traversal used a respawn or correction: " + TravelTap.events());
        assertTrue(!TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), label + ": traversal displayed a loading screen");
    }

    private static boolean arrived(Minecraft client, ResourceKey<Level> dimension, BlockPos door) {
        return client.level.dimension().equals(dimension) && client.player.position().distanceTo(Vec3.atBottomCenterOf(door)) < 2.0D;
    }

    private static void blocked(SeamlessClient client, TestSingleplayerContext world, ResourceKey<Level> destination) {
        client.runOnClient(minecraft -> TravelTap.reset());
        client.holdForwardFor(40);
        client.waitTicks(10);
        assertTrue(client.computeOnClient(minecraft -> minecraft.level.dimension().equals(Level.OVERWORLD)
            && minecraft.player.position().distanceTo(Vec3.atBottomCenterOf(SOURCE)) < 3.0D), "solid destination obstacle allowed the client through the door");
        assertTrue(world.getServer().computeOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            ServerLevel level = Objects.requireNonNull(server.getLevel(destination));
            return player.level() == server.overworld() && player.position().distanceTo(Vec3.atBottomCenterOf(SOURCE)) < 3.0D
                && level.getBlockState(DESTINATION.offset(0, 0, 1)).is(Blocks.STONE)
                && level.getBlockState(DESTINATION.offset(0, 0, -1)).is(Blocks.STONE);
        }), "solid destination obstacle allowed server travel or was removed");
        LOGGER.info("WORMHOLES_DIMENSIONAL_DOOR_PASS blocked_landing real_solid_blocks client_server_source");
    }

    private static void obstruct(ServerLevel level) {
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                level.setBlockAndUpdate(DESTINATION.offset(x, y, -1), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(DESTINATION.offset(x, y, 1), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static Vec3 start(BlockPos door) {
        return new Vec3(door.getX() + 0.5D, door.getY(), door.getZ() - 2.0D);
    }

    private static void waitForServer(ClientGameTestContext context, TestSingleplayerContext world,
                                      Predicate<MinecraftServer> condition, String failure) {
        for (int tick = 0; tick < PREPARE_TICKS; tick++) {
            if (world.getServer().computeOnServer(condition::test)) {
                return;
            }
            context.waitTicks(1);
        }
        throw new AssertionError(failure);
    }
}
