package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.NativeStatusProbe;
import art.arcane.wormholes.network.NativeHandoffProbe;
import art.arcane.wormholes.network.NativeViewProbe;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.PocketBinding;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.wormholes.door.PocketBlockPosition;
import art.arcane.wormholes.door.DoorStateService;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MinecraftDoorGameTest {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final ServerLevel level;
    private final HarnessPlayer player;
    private final EmbeddedChannel channel;
    private final BlockPos source;
    private final BlockPos destination;
    private final List<ItemStack> pair = new ArrayList<>(2);
    private Mob traveler;
    private CompletableFuture<Boolean> persisted;
    private CompletableFuture<Boolean> status;
    private CompletableFuture<Boolean> view;
    private CompletableFuture<Boolean> rules;
    private CompletableFuture<Boolean> mutation;
    private CompletableFuture<Boolean> expansion;
    private CompletableFuture<Boolean> entities;
    private CompletableFuture<Boolean> handoff;
    private CompletableFuture<Boolean> doorMenu;
    private CompletableFuture<Boolean> rescue;
    private ItemStack craftedKit;
    private DoorItemIdentity personal;
    private BlockPos exit;
    private PocketSpace pocket;
    private boolean playerAdded;

    public MinecraftDoorGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "wormholes-test"), false);
        player = new HarnessPlayer(level, cookie);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player, cookie);
        player.initInventoryMenu();
        channel.runPendingTasks();
        channel.outboundMessages().clear();
        source = helper.absolutePos(new BlockPos(3, 2, 10));
        destination = helper.absolutePos(new BlockPos(16, 2, 10));
    }

    public void start() {
        for (int x = 0; x < 23; x++) {
            for (int z = 7; z < 15; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        GameType.CREATIVE.updatePlayerAbilities(player.getAbilities());
        crafting();
        wand();
        player.setItemInHand(InteractionHand.MAIN_HAND, craftedKit);
        helper.assertTrue(runtime.useItem(player, InteractionHand.MAIN_HAND), "Door kit interaction was not consumed");
        runtime.schedule(this::cleanup, 1190);
        helper.startSequence()
            .thenWaitUntil(this::unpacked)
            .thenExecute(() -> place(0, source))
            .thenWaitUntil(() -> placed(0, source))
            .thenExecute(() -> place(1, destination))
            .thenWaitUntil(() -> placed(1, destination))
            .thenExecute(() -> doorMenu = MinecraftDoorMenuGameTest.verify(new MinecraftDoorMenuGameTest.Options(helper, runtime, player,
                runtime.doors().state().findEndpointByItem(identity(0)).orElseThrow())))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, doorMenu, "door_menu"), "Door menu verification did not complete"))
            .thenExecute(this::openAndSpawn)
            .thenIdle(3)
            .thenExecute(this::cross)
            .thenWaitUntil(() -> helper.assertTrue(traveler.getX() > destination.getX() - 2, "Door traveler did not reach paired destination"))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, persisted, "persisted"), "Placed door endpoints were not persisted"))
            .thenExecute(() -> helper.assertTrue(runtime.beforeBreak(player, source.above()), "Upper-half door break did not consume vanilla action"))
            .thenWaitUntil(() -> removed(0, source))
            .thenExecute(() -> helper.assertTrue(runtime.beforeBreak(player, destination), "Destination door break did not consume vanilla action"))
            .thenWaitUntil(() -> removed(1, destination))
            .thenExecute(() -> {
                channel.runPendingTasks();
                helper.assertTrue(!channel.outboundMessages().isEmpty(), "Native player received no inventory packets");
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS door_runtime kit placement traversal persistence upper_half_break removal inventory_packets");
            })
            .thenExecute(this::placePersonal)
            .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(source).is(Blocks.DARK_OAK_DOOR)
                && runtime.doors().state().findEndpointByItem(personal.itemId()).isPresent(), "Personal door was not placed"))
            .thenExecute(this::approachPersonal)
            .thenIdle(3)
            .thenExecute(() -> player.setPos(source.getX() + 0.5, source.getY(), source.getZ() + 1.2))
            .thenWaitUntil(this::enteredPocket)
            .thenExecute(() -> rules = MinecraftPocketRulesGameTest.verify(new MinecraftPocketRulesGameTest.Options(helper, runtime, player, pocket, channel)))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, rules, "rules"), "Pocket rules verification did not complete"))
            .thenExecute(() -> mutation = MinecraftPocketMutationGameTest.verify(new MinecraftPocketMutationGameTest.Options(helper, runtime, player, pocket)))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, mutation, "pocket_mutation"), "Pocket snapshot and resize verification did not complete"))
            .thenExecute(() -> expansion = MinecraftPocketExpansionGameTest.verify(new MinecraftPocketExpansionGameTest.Options(helper, runtime, player, pocket)))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, expansion, "pocket_expansion"), "Pocket expansion and instance verification did not complete"))
            .thenExecute(this::enteredPocket)
            .thenExecute(this::approachReturn)
            .thenIdle(25)
            .thenExecute(() -> player.setPos(exit.getX() + 0.5, exit.getY(), exit.getZ() + 1.2))
            .thenWaitUntil(() -> helper.assertTrue(player.level() == level && player.position().distanceToSqr(Vec3.atCenterOf(source)) < 16,
                "Personal pocket return did not reach source door"))
            .thenWaitUntil(() -> helper.assertTrue(runtime.doors().state().getReturnTicket(player.getUUID()).isEmpty(), "Pocket return ticket was not removed"))
            .thenIdle(25)
            .thenExecute(this::approachPersonal)
            .thenIdle(3)
            .thenExecute(() -> player.setPos(source.getX() + 0.5, source.getY(), source.getZ() + 1.2))
            .thenWaitUntil(this::enteredPocket)
            .thenExecute(() -> rescue = MinecraftPocketRulesGameTest.rescue(new MinecraftPocketRulesGameTest.Options(helper, runtime, player, pocket, channel)))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, rescue, "rescue"), "Pocket lethal rescue did not complete"))
            .thenExecute(() -> helper.assertTrue(runtime.beforeBreak(player, source), "Personal source cleanup was not consumed"))
            .thenWaitUntil(() -> helper.assertTrue(runtime.doors().state().findEndpointByItem(personal.itemId()).isEmpty(), "Personal source endpoint was not removed"))
            .thenExecute(() -> entities = MinecraftEntityProjectionGameTest.run(new MinecraftEntityProjectionGameTest.Options(helper, runtime, player, channel)))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, entities, "entity_projection"), "Entity projection verification did not complete"))
            .thenExecute(() -> {
                cleanup();
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS pocket_runtime personal_entry shell_protection return_door_protection ticket_roundtrip");
                status = NativeStatusProbe.run(runtime);
            })
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, status, "status"), "Signed native status exchange did not complete"))
            .thenExecute(() -> LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS signed_status directory_delivery signed_ack config_restore"))
            .thenExecute(() -> view = NativeViewProbe.run(helper, runtime))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, view, "view"), "Signed native view publication did not complete"))
            .thenExecute(() -> LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS signed_view bulk block_diff entity_state resync unsubscribe"))
            .thenExecute(() -> handoff = NativeHandoffProbe.run(helper, runtime))
            .thenWaitUntil(() -> helper.assertTrue(WormholesGameTests.completed(helper, handoff, "handoff"), "Native handoff verification did not complete"))
            .thenSucceed();
    }

    private void placePersonal() {
        personal = DoorItemIdentity.newPersonal(DoorForm.DOOR);
        player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftDoorItems.door(personal));
        player.setPos(source.getX() + 0.5, source.getY(), source.getZ() - 2);
        player.setYRot(0);
        BlockPos support = source.below();
        helper.assertTrue(runtime.useBlock(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false)), "Personal door placement was not consumed");
    }

    private void approachPersonal() {
        BlockState state = level.getBlockState(source);
        ((DoorBlock) state.getBlock()).setOpen(player, level, state, source, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setPos(source.getX() + 0.5, source.getY(), source.getZ() - 0.5);
        player.setNoGravity(true);
        if (!playerAdded) {
            level.addNewPlayer(player);
            playerAdded = true;
        }
    }

    private void enteredPocket() {
        helper.assertTrue(player.level().dimension().identifier().toString().equals("wormholes:pockets"), "Personal door did not enter pocket dimension");
        pocket = runtime.doors().state().findPocket(PocketBinding.personal(player.getUUID())).orElseThrow();
        PocketLayout layout = new PocketLayout(pocket);
        helper.assertTrue(layout.contains(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ()), "Pocket player arrived outside allocated shell");
        helper.assertTrue(runtime.doors().state().getReturnTicket(player.getUUID()).isPresent(), "Pocket entry did not persist return ticket");
        BlockPos shell = new BlockPos(layout.minX(), layout.minY(), layout.minZ());
        helper.assertTrue(runtime.beforeBreak(player, shell), "Pocket shell break was not protected");
        PocketBlockPosition block = layout.returnDoorLower();
        exit = new BlockPos(block.x(), block.y(), block.z());
        helper.assertTrue(runtime.beforeBreak(player, exit), "Pocket return door break was not protected");
    }

    private void approachReturn() {
        BlockState state = player.level().getBlockState(exit);
        ((DoorBlock) state.getBlock()).setOpen(player, player.level(), state, exit, true);
        player.setPos(exit.getX() + 0.5, exit.getY(), exit.getZ() - 0.5);
    }

    private void crafting() {
        ItemStack rune = MinecraftDoorItems.wormholeRune();
        rune.setCount(2);
        List<ItemStack> ingredients = List.of(new ItemStack(Items.ENDER_EYE, 2), new ItemStack(Items.OAK_DOOR, 2),
            new ItemStack(Items.ENDER_EYE, 2), new ItemStack(Items.OBSIDIAN, 2), rune, new ItemStack(Items.OBSIDIAN, 2),
            ItemStack.EMPTY, new ItemStack(Items.OAK_DOOR, 2), ItemStack.EMPTY);
        CraftingMenu menu = new CraftingMenu(7, player.getInventory(), ContainerLevelAccess.create(level, source));
        for (int index = 0; index < ingredients.size(); index++) {
            menu.getInputGridSlots().get(index).set(ingredients.get(index).copy());
        }
        helper.assertTrue(MinecraftDoorItems.kit(menu.getResultSlot().getItem()).isPresent(), "Loaded native door recipe did not match live crafting grid");
        helper.assertTrue(CrafterBlock.getPotentialResults(level, CraftingInput.of(3, 3, ingredients)).isEmpty(),
            "Automatic crafter accepted an identity-bearing door recipe");
        player.administrator = false;
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty() && menu.getInputGridSlots().getFirst().getItem().getCount() == 2,
            "Unprivileged player consumed dimensional door ingredients");
        player.administrator = true;
        menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertTrue(menu.getCarried().isEmpty() && menu.getInputGridSlots().getFirst().getItem().getCount() == 2,
            "Shift crafting consumed dimensional door ingredients");
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        craftedKit = menu.getCarried().copy();
        helper.assertTrue(MinecraftDoorItems.kit(craftedKit).isPresent()
            && menu.getInputGridSlots().getFirst().getItem().getCount() == 1, "Authorized craft did not mint one kit and consume one recipe");
        helper.assertTrue(!MinecraftDoorItems.kit(craftedKit).equals(MinecraftDoorItems.kit(menu.getResultSlot().getItem())),
            "Consecutive crafts reused the same paired-door identity");
        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().clearContent();
        LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS crafting_runtime recipe permission shift_guard crafter_guard unique_identity consumption");
    }

    private void wand() {
        BlockPos first = helper.absolutePos(new BlockPos(9, 2, 4));
        BlockPos last = helper.absolutePos(new BlockPos(11, 4, 4));
        player.setPos(Vec3.atBottomCenterOf(first).add(0, 0, -3));
        player.setYRot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftPortalTools.wand());
        helper.assertTrue(runtime.attackBlock(player, first), "Wand first corner was not consumed");
        helper.assertTrue(runtime.useBlock(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(last), Direction.NORTH, last, false)), "Wand second corner was not consumed");
        channel.runPendingTasks();
        int displays = 0;
        int metadata = 0;
        for (Object packet : channel.outboundMessages()) {
            if (packet instanceof ClientboundAddEntityPacket added && added.getType() == EntityTypes.BLOCK_DISPLAY) {
                displays++;
                helper.assertTrue(level.getEntity(added.getId()) == null, "Private selection display entered the shared world");
            } else if (packet instanceof ClientboundSetEntityDataPacket) {
                metadata++;
            }
        }
        helper.assertTrue(displays == 2 && metadata == 2, "Wand selection did not send both private displays and metadata");
        helper.assertTrue(runtime.attackBlock(player, first), "Wand build interaction was not consumed");
        MinecraftPortal portal = runtime.portals().at(level, first);
        helper.assertTrue(portal != null, "Wand selection did not build an actual portal");
        helper.assertTrue(runtime.portals().remove(player, portal.getId()), "Wand portal cleanup failed");
        channel.runPendingTasks();
        int removals = 0;
        for (Object packet : channel.outboundMessages()) {
            if (packet instanceof ClientboundRemoveEntitiesPacket) {
                removals++;
            }
        }
        helper.assertTrue(removals == 2, "Wand build left private selection entities visible");
        channel.outboundMessages().clear();
        LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS wand_runtime selection private_display_packets build cleanup");
    }

    private void unpacked() {
        pair.clear();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (MinecraftDoorItems.identity(item).isPresent()) {
                pair.add(item.copy());
            }
        }
        helper.assertTrue(pair.size() == 2, "Door kit did not unpack two tagged endpoints");
        DoorItemIdentity first = MinecraftDoorItems.identity(pair.get(0)).orElseThrow();
        DoorItemIdentity second = MinecraftDoorItems.identity(pair.get(1)).orElseThrow();
        helper.assertTrue(first.pairId().equals(second.pairId()) && first.pairEndpoint() != second.pairEndpoint(),
            "Door kit identities do not form a pair");
        player.getInventory().clearContent();
    }

    private void place(int index, BlockPos target) {
        player.setPos(target.getX() + 0.5, target.getY(), target.getZ() - 2);
        player.setYRot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, pair.get(index).copy());
        BlockPos support = target.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
        helper.assertTrue(runtime.useBlock(player, InteractionHand.MAIN_HAND, hit), "Tagged door placement did not consume interaction");
    }

    private void placed(int index, BlockPos target) {
        helper.assertTrue(level.getBlockState(target).is(Blocks.OAK_DOOR), "Tagged door lower half was not placed");
        helper.assertTrue(level.getBlockState(target.above()).is(Blocks.OAK_DOOR), "Tagged door upper half was not placed");
        helper.assertTrue(runtime.doors().state().findEndpointByItem(identity(index)).isPresent(), "Door endpoint was not registered");
    }

    private void openAndSpawn() {
        BlockState state = level.getBlockState(source);
        ((DoorBlock) state.getBlock()).setOpen(player, level, state, source, true);
        traveler = helper.spawn(EntityTypes.PIG, helper.relativePos(source).getX() + 0.5F,
            helper.relativePos(source).getY(), helper.relativePos(source).getZ() - 0.5F);
        traveler.setNoAi(true);
        traveler.setNoGravity(true);
        persisted = CompletableFuture.supplyAsync(() -> {
            try {
                DoorStateService loaded = DoorStateService.under(runtime.server().getServerDirectory().resolve("config/wormholes"), MinecraftJsonDocuments.INSTANCE);
                return loaded.findEndpointByItem(identity(0)).isPresent() && loaded.findEndpointByItem(identity(1)).isPresent();
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        });
    }

    private void cross() {
        traveler.setDeltaMovement(0, 0, 0);
        traveler.setPos(source.getX() + 0.5, source.getY(), source.getZ() + 1.2);
    }

    private void removed(int index, BlockPos target) {
        helper.assertTrue(level.getBlockState(target).isAir() && level.getBlockState(target.above()).isAir(), "Door break left a physical half");
        helper.assertTrue(runtime.doors().state().findEndpointByItem(identity(index)).isEmpty(), "Door break left registered endpoint");
    }

    private UUID identity(int index) {
        return MinecraftDoorItems.identity(pair.get(index)).orElseThrow().itemId();
    }

    private void cleanup() {
        if (traveler != null) {
            traveler.discard();
        }
        if (playerAdded && !player.isRemoved()) {
            player.discard();
        }
        channel.finishAndReleaseAll();
    }

    private static final class HarnessPlayer extends ServerPlayer {
        private boolean administrator = true;
        private HarnessPlayer(ServerLevel level, CommonListenerCookie cookie) {
            super(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        }

        @Override
        public GameType gameMode() {
            return GameType.CREATIVE;
        }

        @Override
        public CommandSourceStack createCommandSourceStack() {
            return administrator ? super.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER) : super.createCommandSourceStack();
        }
    }
}
