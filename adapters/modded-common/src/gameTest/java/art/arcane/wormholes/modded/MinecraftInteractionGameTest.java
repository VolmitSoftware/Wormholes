package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinecraftInteractionGameTest {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime = WormholesGameTests.RUNTIME;
    private final BlockPos first = new BlockPos(128, 80, 128);
    private final BlockPos last = first.offset(1, 2, 0);
    private final BlockPos door = first.offset(5, 0, 0);
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private MinecraftGameTestPlayer connection;
    private AutoCloseable permissions;
    private UUID portalId;
    private DoorItemIdentity boundDoor;
    private int sequence;

    public MinecraftInteractionGameTest(GameTestHelper helper) {
        this.helper = helper;
    }

    public CompletableFuture<Boolean> run() {
        try {
            connection = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "interaction");
            ServerPlayer player = connection.player();
            player.setGameMode(GameType.CREATIVE);
            permissions = runtime.access().register((actor, node) -> actor != player ? MinecraftAccessService.Decision.UNSET
                : node.equals("wormholes.admin") ? MinecraftAccessService.Decision.DENY : MinecraftAccessService.Decision.ALLOW);
            for (int x = -2; x < 9; x++) {
                for (int y = -1; y < 5; y++) {
                    for (int z = -5; z < 3; z++) {
                        player.level().setBlock(first.offset(x, y, z), y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            aim(player, first);
            helper.startSequence()
                .thenExecute(() -> select(player))
                .thenIdle(4)
                .thenExecute(() -> airBuild(player))
                .thenIdle(4)
                .thenExecute(() -> leftMenu(player))
                .thenIdle(4)
                .thenExecute(() -> rightMenu(player))
                .thenIdle(4)
                .thenExecute(() -> entityMenu(player))
                .thenIdle(4)
                .thenExecute(() -> frameMenu(player))
                .thenIdle(4)
                .thenExecute(() -> deniedAndOccluded(player))
                .thenExecute(() -> {
                    player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftDoorItems.pairKit(DoorForm.DOOR));
                    useAir(player, InteractionHand.MAIN_HAND);
                })
                .thenWaitUntil(() -> helper.assertTrue(findDoor(player) != null, "Packet kit use did not yield bound doors"))
                .thenExecute(() -> placeDoor(player))
                .thenWaitUntil(() -> helper.assertTrue(runtime.doors().state().findEndpointByItem(boundDoor.itemId()).isPresent()
                    && !player.level().getBlockState(door).isAir() && !player.level().getBlockState(door.above()).isAir(), "Packet door placement did not persist and apply"))
                .thenExecute(() -> {
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    attack(player, door.above());
                })
                .thenWaitUntil(() -> helper.assertTrue(runtime.doors().state().findEndpointByItem(boundDoor.itemId()).isEmpty()
                    && player.level().getBlockState(door).isAir() && player.level().getBlockState(door.above()).isAir(), "Creative upper door break did not remove endpoint and apply block cleanup"))
                .thenExecute(() -> {
                    long drops = player.level().getEntitiesOfClass(ItemEntity.class, new AABB(door).inflate(2)).stream()
                        .filter(entity -> MinecraftDoorItems.identity(entity.getItem()).filter(boundDoor::equals).isPresent()).count();
                    helper.assertTrue(drops == 1, "Creative break did not return exactly one identity-preserving door");
                    helper.assertTrue(player.level().getBlockState(door).isAir() && player.level().getBlockState(door.above()).isAir(), "Creative door break retained a physical half");
                    LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS interaction_runtime production_callbacks packet_selection creative_cancel swing_dedup air_build air_menu entity_menu frame_menu offhand_guard permission_guard obstruction_guard creative_bound_item");
                    result.complete(true);
                });
            runtime.schedule(() -> result.completeExceptionally(new IllegalStateException("Packet interaction test timed out")), 1150);
            return result.whenCompleteAsync((value, failure) -> close(), runtime.server());
        } catch (Exception failure) {
            close();
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void select(ServerPlayer player) {
        player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftPortalItems.of(runtime).wand());
        player.level().setBlock(first, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        player.level().setBlock(last, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        attack(player, first);
        player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        helper.assertTrue(player.level().getBlockState(first).is(Blocks.STONE), "Creative wand selection broke the first corner");
        helper.assertTrue(runtime.portals().at(player.level(), first) == null, "Block attack plus swing built before second corner");
        useBlock(player, last, Direction.NORTH);
        player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        helper.assertTrue(runtime.portals().at(player.level(), first) == null, "Second-corner swing prematurely built selection");
        helper.assertTrue(connection.messages().stream().anyMatch(message -> message.getString().startsWith("Selected 6 blocks.")),
            "Packet selection did not complete: " + connection.messages().stream().map(message -> message.getString()).toList()
                + "; outbound=" + connection.channel().outboundMessages().stream().map(packet -> packet.getClass().getSimpleName()).distinct().toList());
        player.level().setBlock(first, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        player.level().setBlock(last, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private void airBuild(ServerPlayer player) {
        aim(player, first);
        player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        MinecraftPortal portal = runtime.portals().at(player.level(), first);
        helper.assertTrue(portal != null, "Air swing at private selection did not construct portal");
        portalId = portal.getId();
        helper.assertTrue(portal.getGeometry().getBlockPositions().size() == 6, "Packet selection lost aperture cells");
    }

    private void leftMenu(ServerPlayer player) {
        aim(player, first);
        player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.OFF_HAND));
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Offhand swing opened portal menu");
        player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        menu(player, "Air left click");
    }

    private void rightMenu(ServerPlayer player) {
        useAir(player, InteractionHand.OFF_HAND);
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Offhand use opened portal menu");
        useAir(player, InteractionHand.MAIN_HAND);
        menu(player, "Air right click");
        player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Right-click followup swing reopened portal menu");
    }

    private void entityMenu(ServerPlayer player) {
        player.connection.handleInteract(new ServerboundInteractPacket(Integer.MAX_VALUE, InteractionHand.MAIN_HAND, Vec3.ZERO, false));
        menu(player, "Private projected entity click");
    }

    private void frameMenu(ServerPlayer player) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        useBlock(player, first.below(), Direction.UP);
        menu(player, "Sneaking empty-hand frame click");
        player.setShiftKeyDown(false);
    }

    private void deniedAndOccluded(ServerPlayer player) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftPortalItems.of(runtime).wand());
        portal.setOwner(UUID.randomUUID());
        useAir(player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Unauthorized wand click opened management menu");
        portal.setOwner(player.getUUID());
        BlockPos obstruction = first.offset(0, 1, -1);
        player.level().setBlock(obstruction, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        useAir(player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Wand clicked portal through foreground wall");
        player.level().setBlock(obstruction, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private void placeDoor(ServerPlayer player) {
        ItemStack item = findDoor(player);
        boundDoor = MinecraftDoorItems.identity(item).orElseThrow();
        player.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
        aim(player, door);
        useBlock(player, door.below(), Direction.UP);
    }

    private ItemStack findDoor(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (MinecraftDoorItems.identity(item).isPresent()) {
                return item;
            }
        }
        return null;
    }

    private void attack(ServerPlayer player, BlockPos position) {
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, Direction.NORTH, ++sequence));
    }

    private void useBlock(ServerPlayer player, BlockPos position, Direction face) {
        Vec3 hit = Vec3.atCenterOf(position).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, position, false), ++sequence));
    }

    private void useAir(ServerPlayer player, InteractionHand hand) {
        player.connection.handleUseItem(new ServerboundUseItemPacket(hand, ++sequence, player.getYRot(), player.getXRot()));
    }

    private void aim(ServerPlayer player, BlockPos target) {
        player.setPos(target.getX() + 0.5, target.getY(), target.getZ() - 3);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(target.getX() + 0.5, target.getY() + 1.5, target.getZ() + 0.5));
    }

    private void menu(ServerPlayer player, String label) {
        helper.assertTrue(player.containerMenu != player.inventoryMenu, label + " did not open portal management");
        player.closeContainer();
    }

    private void close() {
        if (portalId != null) {
            runtime.portals().remove(portalId);
        }
        if (connection != null) {
            connection.close();
        }
        if (permissions != null) {
            try {
                permissions.close();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
