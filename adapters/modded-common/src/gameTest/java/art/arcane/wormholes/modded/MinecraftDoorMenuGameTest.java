package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.localization.DoorViewMessages;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class MinecraftDoorMenuGameTest {
    private final Options options;
    private final MinecraftDoorService doors;
    private final UUID itemId;
    private final ServerPlayer stranger;
    private final EmbeddedChannel channel;
    private final boolean projectionEnabled;
    private MinecraftInventoryMenu menu;

    private MinecraftDoorMenuGameTest(Options options) {
        this.options = options;
        doors = options.runtime().doors();
        itemId = options.endpoint().identity().itemId();
        projectionEnabled = options.runtime().configuration().settings().getDoors().projectionEnabled;
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "door-visitor"), false);
        stranger = new ServerPlayer(options.player().level().getServer(), options.player().level(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        stranger.connection = new ServerGamePacketListenerImpl(options.player().level().getServer(), connection, stranger, cookie);
        stranger.initInventoryMenu();
        stranger.setPos(options.player().position());
    }

    public static CompletableFuture<Boolean> verify(Options options) {
        return new MinecraftDoorMenuGameTest(options).run();
    }

    private CompletableFuture<Boolean> run() {
        options.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        options.player().setShiftKeyDown(true);
        options.helper().assertTrue(options.runtime().useBlock(options.player(), InteractionHand.MAIN_HAND, hit()),
            "Owner sneak interaction did not open door access controls");
        options.player().setShiftKeyDown(false);
        options.helper().assertTrue(options.player().containerMenu instanceof MinecraftInventoryMenu, "Door access container was not opened");
        menu = (MinecraftInventoryMenu) options.player().containerMenu;
        options.helper().assertTrue(!doors.canManage(stranger, itemId), "Unprivileged stranger can manage dimensional door");
        DoorOpenState original = options.endpoint().openState();
        menu.clicked(4, 0, ContainerInput.PICKUP, options.player());
        return await(() -> doors.state().findEndpointByItem(itemId).orElseThrow().openState() == original.flipped()
                && menu.getContainer().getItem(4).is(original == DoorOpenState.OPEN ? Items.DYE.gray() : Items.DYE.lime()), 0)
            .thenCompose(ignored -> {
                menu.clicked(4, 0, ContainerInput.PICKUP, options.player());
                return await(() -> doors.state().findEndpointByItem(itemId).orElseThrow().openState() == original
                    && menu.getContainer().getItem(4).is(original == DoorOpenState.OPEN ? Items.DYE.lime() : Items.DYE.gray()), 0);
            }).thenCompose(ignored -> projection()).thenCompose(ignored -> surface()).thenCompose(ignored -> doors.addAccessPlayer(options.player(), itemId, stranger.getUUID()))
            .thenComposeAsync(added -> {
                options.helper().assertTrue(added, "Could not add door access test player");
                menu.refresh();
                menu.clicked(9, 0, ContainerInput.PICKUP, options.player());
                return await(() -> listedState() == DoorAccessState.WHITELIST && menu.getContainer().getItem(9).is(Items.STAINED_GLASS_PANE.green()), 0);
            }, options.player().level().getServer()).thenCompose(ignored -> {
                menu.clicked(9, 1, ContainerInput.PICKUP, options.player());
                return await(() -> listedState() == DoorAccessState.BLACKLIST && menu.getContainer().getItem(9).is(Items.STAINED_GLASS_PANE.red()), 0);
            }).thenCompose(ignored -> {
                options.helper().assertTrue(options.runtime().useBlock(stranger, InteractionHand.MAIN_HAND, hit()),
                    "Blacklisted player door interaction was not denied");
                menu.clicked(9, 0, ContainerInput.QUICK_MOVE, options.player());
                return await(() -> listedState() == null && menu.getContainer().getItem(9).isEmpty(), 0);
            }).thenApply(ignored -> {
                options.helper().assertTrue(menu.getCarried().isEmpty(), "Door menu click moved a control item into player cursor");
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS door_menu sneak_open owner_guard open_state projection_master_guard projection_cycle private_surface animated_metadata surface_cleanup whitelist blacklist interaction_deny shift_remove item_safety");
                return true;
            }).whenCompleteAsync((passed, failure) -> {
                options.runtime().configuration().settings().getDoors().projectionEnabled = projectionEnabled;
                options.player().closeContainer();
                if (!stranger.isRemoved()) {
                    stranger.discard();
                }
                channel.finishAndReleaseAll();
            }, options.player().level().getServer());
    }

    private CompletableFuture<Boolean> surface() {
        BlockPos position = hit().getBlockPos();
        BlockState original = stranger.level().getBlockState(position);
        stranger.level().addNewPlayer(stranger);
        channel.outboundMessages().clear();
        ((DoorBlock) original.getBlock()).setOpen(null, stranger.level(), original, position, true);
        Set<Integer> displays = new HashSet<>();
        return await(() -> {
            channel.runPendingTasks();
            int metadata = 0;
            for (Object packet : channel.outboundMessages()) {
                if (packet instanceof ClientboundAddEntityPacket spawn && spawn.getType() == EntityTypes.BLOCK_DISPLAY) {
                    displays.add(spawn.getId());
                    options.helper().assertTrue(stranger.level().getEntity(spawn.getId()) == null, "Door surface entity entered shared world");
                } else if (packet instanceof ClientboundSetEntityDataPacket update && displays.contains(update.id())) {
                    metadata++;
                }
            }
            return displays.size() == 2 && metadata > 2;
        }, 0).thenCompose(ignored -> {
            channel.outboundMessages().clear();
            BlockState active = stranger.level().getBlockState(position);
            ((DoorBlock) active.getBlock()).setOpen(null, stranger.level(), active, position, false);
            return await(() -> {
                channel.runPendingTasks();
                Set<Integer> removed = new HashSet<>();
                for (Object packet : channel.outboundMessages()) {
                    if (packet instanceof ClientboundRemoveEntitiesPacket destroy) {
                        for (int id : displays) {
                            if (destroy.getEntityIds().contains(id)) {
                                removed.add(id);
                            }
                        }
                    }
                }
                return removed.equals(displays);
            }, 0);
        }).whenComplete((passed, failure) -> {
            BlockState current = stranger.level().getBlockState(position);
            ((DoorBlock) current.getBlock()).setOpen(null, stranger.level(), current, position, original.getValue(DoorBlock.OPEN));
        });
    }

    private CompletableFuture<Boolean> projection() {
        DoorProjectionState original = doors.state().findEndpointByItem(itemId).orElseThrow().projection();
        options.runtime().configuration().settings().getDoors().projectionEnabled = false;
        menu.clicked(2, 0, ContainerInput.PICKUP, options.player());
        options.helper().assertTrue(doors.state().findEndpointByItem(itemId).orElseThrow().projection() == original,
            "Disabled global projection allowed an endpoint override");
        options.runtime().configuration().settings().getDoors().projectionEnabled = true;
        return projectionStep(DoorProjectionState.ON).thenCompose(ignored -> projectionStep(DoorProjectionState.OFF))
            .thenCompose(ignored -> projectionStep(DoorProjectionState.INHERIT));
    }

    private CompletableFuture<Boolean> projectionStep(DoorProjectionState expected) {
        TextKey key = switch (expected) {
            case INHERIT -> DoorViewMessages.STATE_INHERIT;
            case ON -> DoorViewMessages.STATE_ON;
            case OFF -> DoorViewMessages.STATE_OFF;
        };
        String label = MinecraftMenuText.item(options.player(), Items.SPYGLASS, DoorViewMessages.MENU_PROJECTION,
            Map.of("state", MinecraftMenuText.text(options.player(), key, Map.of()).getString())).getHoverName().getString();
        menu.clicked(2, 0, ContainerInput.PICKUP, options.player());
        return await(() -> doors.state().findEndpointByItem(itemId).orElseThrow().projection() == expected
            && menu.getContainer().getItem(2).getHoverName().getString().equals(label), 0);
    }

    private DoorAccessState listedState() {
        return doors.state().accessRecord(itemId).orElseThrow().stateOf(stranger.getUUID());
    }

    private BlockHitResult hit() {
        BlockPos block = new BlockPos(options.endpoint().position().x(), options.endpoint().position().y(), options.endpoint().position().z());
        return new BlockHitResult(Vec3.atCenterOf(block), Direction.NORTH, block, false);
    }

    private CompletableFuture<Boolean> await(BooleanSupplier condition, int ticks) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!options.runtime().schedule(() -> {
            try {
                if (condition.getAsBoolean()) {
                    result.complete(true);
                } else if (ticks >= 100) {
                    result.completeExceptionally(new IllegalStateException("Door menu change did not persist within 100 ticks"));
                } else {
                    await(condition, ticks + 1).whenComplete((value, failure) -> {
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }, 1L)) {
            result.completeExceptionally(new IllegalStateException("Door menu test scheduler stopped"));
        }
        return result;
    }

    public record Options(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer player, PlacedDoorEndpoint endpoint) {
    }
}
