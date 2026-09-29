package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.localization.DoorViewMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class MinecraftDoorMenuGameTest {
    private static final int PLACARD_SLOT = 3;
    private static final int OPEN_STATE_SLOT = 4;
    private static final int ADD_SLOT = 5;
    private static final int PROJECTION_SLOT = 6;
    private static final int FIRST_ENTRY_SLOT = 9;

    private final Options options;
    private final MinecraftDoorService doors;
    private final UUID itemId;
    private final ServerPlayer stranger;
    private final EmbeddedChannel strangerChannel;
    private final boolean projectionEnabled;

    private MinecraftDoorMenuGameTest(Options options) {
        this.options = options;
        doors = options.runtime().doors();
        itemId = options.endpoint().identity().itemId();
        projectionEnabled = options.runtime().configuration().settings().getDoors().projectionEnabled;
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "door-visitor"), false);
        stranger = new ServerPlayer(options.player().level().getServer(), options.player().level(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        strangerChannel = new EmbeddedChannel(connection);
        stranger.connection = new ServerGamePacketListenerImpl(options.player().level().getServer(), connection, stranger, cookie);
        stranger.initInventoryMenu();
        stranger.setPos(options.player().position());
    }

    public static CompletableFuture<Boolean> verify(Options options) {
        return new MinecraftDoorMenuGameTest(options).run();
    }

    private CompletableFuture<Boolean> run() {
        ServerPlayer owner = options.player();
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        owner.setShiftKeyDown(true);
        options.helper().assertTrue(options.runtime().useBlock(owner, InteractionHand.MAIN_HAND, hit()),
            "Owner sneak interaction did not open door access controls");
        owner.setShiftKeyDown(false);
        header(1);
        options.helper().assertTrue(!doors.canManage(stranger, itemId), "Unprivileged stranger can manage dimensional door");
        DoorOpenState original = endpoint().openState();
        click(OPEN_STATE_SLOT, 0, ContainerInput.PICKUP);
        return await(() -> endpoint().openState() == original.flipped() && item(OPEN_STATE_SLOT).is(openIcon(original.flipped())))
            .thenCompose(ignored -> {
                click(OPEN_STATE_SLOT, 0, ContainerInput.PICKUP);
                return await(() -> endpoint().openState() == original && item(OPEN_STATE_SLOT).is(openIcon(original)));
            }).thenCompose(ignored -> projection()).thenCompose(ignored -> surface())
            .thenCompose(ignored -> prompt("cancel")).thenCompose(ignored -> {
                header(1);
                options.runtime().server().services().nameToIdCache().add(new NameAndId(owner.getUUID(), owner.getGameProfile().name()));
                options.runtime().server().services().nameToIdCache().add(new NameAndId(stranger.getUUID(), stranger.getGameProfile().name()));
                return prompt(owner.getGameProfile().name());
            }).thenCompose(ignored -> {
                header(1);
                options.helper().assertTrue(chatted(WormholesMessages.DOOR_ACCESS_OWNER_ALWAYS, MessageArgs.empty()),
                    "Owner name prompt did not send the owner notice to chat");
                options.helper().assertTrue(record().players().isEmpty(), "Owner name prompt listed the owner");
                return prompt(stranger.getGameProfile().name());
            }).thenCompose(ignored -> await(() -> listedState() == DoorAccessState.NEUTRAL && rows() == 2
                && item(FIRST_ENTRY_SLOT).is(Items.STAINED_GLASS_PANE.black())
                && item(FIRST_ENTRY_SLOT).getHoverName().getString().equals(stranger.getGameProfile().name())))
            .thenCompose(ignored -> {
                header(2);
                options.helper().assertTrue(chatted(WormholesMessages.DOOR_ACCESS_ADDED,
                    MinecraftPortalText.arguments("name", stranger.getGameProfile().name())), "Added player notice was not sent to chat");
                for (int slot = FIRST_ENTRY_SLOT + 1; slot < 18; slot++) {
                    options.helper().assertTrue(item(slot).is(Items.STAINED_GLASS_PANE.gray()), "Empty access entry slot " + slot + " is not a gray pane");
                }
                click(FIRST_ENTRY_SLOT, 0, ContainerInput.PICKUP);
                return await(() -> listedState() == DoorAccessState.WHITELIST && item(FIRST_ENTRY_SLOT).is(Items.STAINED_GLASS_PANE.green()));
            }).thenCompose(ignored -> {
                click(FIRST_ENTRY_SLOT, 1, ContainerInput.PICKUP);
                return await(() -> listedState() == DoorAccessState.BLACKLIST && item(FIRST_ENTRY_SLOT).is(Items.STAINED_GLASS_PANE.red()));
            }).thenCompose(ignored -> {
                options.helper().assertTrue(options.runtime().useBlock(stranger, InteractionHand.MAIN_HAND, hit()),
                    "Blacklisted player door interaction was not denied");
                click(FIRST_ENTRY_SLOT, 0, ContainerInput.QUICK_MOVE);
                return await(() -> listedState() == null && window() != null && rows() == 1);
            }).thenApply(ignored -> {
                header(1);
                options.helper().assertTrue(owner.containerMenu.getCarried().isEmpty(), "Door menu click moved a control item into player cursor");
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS door_menu sneak_open title gray_pane header_layout owner_guard open_state projection_master_guard projection_cycle private_surface animated_metadata surface_cleanup prompt_cancel prompt_owner prompt_add list_height whitelist blacklist interaction_deny shift_remove item_safety");
                return true;
            }).whenCompleteAsync((passed, failure) -> {
                options.runtime().configuration().settings().getDoors().projectionEnabled = projectionEnabled;
                owner.closeContainer();
                if (!stranger.isRemoved()) {
                    stranger.discard();
                }
                strangerChannel.finishAndReleaseAll();
            }, owner.level().getServer());
    }

    private void header(int rows) {
        ServerPlayer owner = options.player();
        MinecraftWindow window = window();
        options.helper().assertTrue(window != null && window.isVisible(), "Door access window is not open");
        options.helper().assertTrue(rows() == rows, "Door access window has " + rows() + " rows instead of " + rows);
        String kind = MinecraftMenuText.text(owner, WormholesMessages.DOOR_ACCESS_KIND_PAIR, MessageArgs.empty()).getString();
        options.helper().assertTrue(window.getTitle().equals(MinecraftLegacyText.text(owner, WormholesMessages.DOOR_MENU_ACCESS_TITLE,
            MinecraftPortalText.arguments("kind", kind))), "Door access title differs from the localized title");
        options.helper().assertTrue(item(PLACARD_SLOT).is(MinecraftDoorItems.material(options.endpoint().identity()))
            && item(PLACARD_SLOT).getHoverName().getString().equals(placardName(kind)),
            "Door access placard is not at the header left of center");
        options.helper().assertTrue(item(OPEN_STATE_SLOT).is(openIcon(endpoint().openState())), "Open state control is not centered");
        options.helper().assertTrue(item(ADD_SLOT).is(Items.WRITABLE_BOOK), "Add player control is not right of center");
        options.helper().assertTrue(item(PROJECTION_SLOT).is(Items.SPYGLASS), "Projection control is not beside the add control");
        for (int slot : List.of(0, 1, 2, 7, 8)) {
            options.helper().assertTrue(item(slot).is(Items.STAINED_GLASS_PANE.gray()), "Header slot " + slot + " is not a gray pane");
        }
    }

    private String placardName(String kind) {
        List<String> lines = MinecraftLegacyText.lines(options.player(), WormholesMessages.DOOR_MENU_ACCESS_PLACARD, MinecraftPortalText.arguments(
            "kind", kind, "owner", options.player().getGameProfile().name(), "whitelisted", 0, "blacklisted", 0, "count", record().players().size()));
        return MinecraftLegacyText.component(lines.getFirst()).getString();
    }

    private CompletableFuture<Boolean> prompt(String input) {
        click(ADD_SLOT, 0, ContainerInput.PICKUP);
        return await(() -> window() == null && !(options.player().containerMenu instanceof MinecraftWindowMenu)).thenCompose(ignored -> {
            options.helper().assertTrue(chatted(WormholesMessages.DOOR_ACCESS_PROMPT_PLAYER, MinecraftPortalText.arguments("cancel",
                MinecraftMenuText.text(options.player(), WormholesMessages.PORTAL_INPUT_CANCEL, MessageArgs.empty()).getString())),
                "Door access prompt was not sent to chat");
            options.helper().assertTrue(MinecraftChatInput.chat(options.player(), input), "Door access prompt did not consume chat input");
            return await(() -> window() != null && options.player().containerMenu instanceof MinecraftWindowMenu);
        });
    }

    private boolean chatted(TextKey key, MessageArgs arguments) {
        String expected = MinecraftMenuText.text(options.player(), key, arguments).getString();
        options.channel().runPendingTasks();
        for (Object packet : options.channel().outboundMessages()) {
            if (packet instanceof ClientboundSystemChatPacket chat && !chat.overlay() && chat.content().getString().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private CompletableFuture<Boolean> surface() {
        BlockPos position = hit().getBlockPos();
        BlockState original = stranger.level().getBlockState(position);
        stranger.level().addNewPlayer(stranger);
        strangerChannel.outboundMessages().clear();
        ((DoorBlock) original.getBlock()).setOpen(null, stranger.level(), original, position, true);
        Set<Integer> displays = new HashSet<>();
        return await(() -> {
            strangerChannel.runPendingTasks();
            int metadata = 0;
            for (Object packet : strangerChannel.outboundMessages()) {
                if (packet instanceof ClientboundAddEntityPacket spawn && spawn.getType() == EntityTypes.BLOCK_DISPLAY) {
                    displays.add(spawn.getId());
                    options.helper().assertTrue(stranger.level().getEntity(spawn.getId()) == null, "Door surface entity entered shared world");
                } else if (packet instanceof ClientboundSetEntityDataPacket update && displays.contains(update.id())) {
                    metadata++;
                }
            }
            return displays.size() == 2 && metadata > 2;
        }).thenCompose(ignored -> {
            strangerChannel.outboundMessages().clear();
            BlockState active = stranger.level().getBlockState(position);
            ((DoorBlock) active.getBlock()).setOpen(null, stranger.level(), active, position, false);
            return await(() -> {
                strangerChannel.runPendingTasks();
                Set<Integer> removed = new HashSet<>();
                for (Object packet : strangerChannel.outboundMessages()) {
                    if (packet instanceof ClientboundRemoveEntitiesPacket destroy) {
                        for (int id : displays) {
                            if (destroy.entityIds().contains(id)) {
                                removed.add(id);
                            }
                        }
                    }
                }
                return removed.equals(displays);
            });
        }).whenComplete((passed, failure) -> {
            BlockState current = stranger.level().getBlockState(position);
            ((DoorBlock) current.getBlock()).setOpen(null, stranger.level(), current, position, original.getValue(DoorBlock.OPEN));
        });
    }

    private CompletableFuture<Boolean> projection() {
        DoorProjectionState original = endpoint().projection();
        options.runtime().configuration().settings().getDoors().projectionEnabled = false;
        options.channel().outboundMessages().clear();
        click(PROJECTION_SLOT, 0, ContainerInput.PICKUP);
        return await(() -> chatted(DoorViewMessages.DISABLED, MessageArgs.empty())).thenCompose(ignored -> {
            options.helper().assertTrue(endpoint().projection() == original, "Disabled global projection allowed an endpoint override");
            options.runtime().configuration().settings().getDoors().projectionEnabled = true;
            return projectionStep(DoorProjectionState.ON);
        }).thenCompose(ignored -> projectionStep(DoorProjectionState.OFF))
            .thenCompose(ignored -> projectionStep(DoorProjectionState.INHERIT));
    }

    private CompletableFuture<Boolean> projectionStep(DoorProjectionState expected) {
        TextKey key = switch (expected) {
            case INHERIT -> DoorViewMessages.STATE_INHERIT;
            case ON -> DoorViewMessages.STATE_ON;
            case OFF -> DoorViewMessages.STATE_OFF;
        };
        String state = MinecraftMenuText.text(options.player(), key, MessageArgs.empty()).getString();
        String label = MinecraftLegacyText.component(MinecraftLegacyText.lines(options.player(), DoorViewMessages.MENU_PROJECTION,
            MinecraftPortalText.arguments("state", state)).getFirst()).getString();
        click(PROJECTION_SLOT, 0, ContainerInput.PICKUP);
        return await(() -> endpoint().projection() == expected && item(PROJECTION_SLOT).getHoverName().getString().equals(label));
    }

    private void click(int slot, int button, ContainerInput input) {
        options.player().containerMenu.clicked(slot, button, input, options.player());
    }

    private MinecraftWindow window() {
        return MinecraftWindow.active(options.player());
    }

    private int rows() {
        return options.player().containerMenu instanceof MinecraftWindowMenu menu ? menu.getRowCount() : 0;
    }

    private ItemStack item(int slot) {
        return options.player().containerMenu instanceof MinecraftWindowMenu menu && slot < menu.size() ? menu.item(slot) : ItemStack.EMPTY;
    }

    private static Item openIcon(DoorOpenState state) {
        return state == DoorOpenState.OPEN ? Items.DYE.lime() : Items.DYE.gray();
    }

    private PlacedDoorEndpoint endpoint() {
        return doors.state().findEndpointByItem(itemId).orElseThrow();
    }

    private DoorAccessRecord record() {
        return doors.state().accessRecord(itemId).orElseThrow();
    }

    private DoorAccessState listedState() {
        return record().stateOf(stranger.getUUID());
    }

    private BlockHitResult hit() {
        BlockPos block = new BlockPos(options.endpoint().position().x(), options.endpoint().position().y(), options.endpoint().position().z());
        return new BlockHitResult(Vec3.atCenterOf(block), Direction.NORTH, block, false);
    }

    private CompletableFuture<Boolean> await(BooleanSupplier condition) {
        return await(condition, 0);
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

    public record Options(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer player, EmbeddedChannel channel,
                          PlacedDoorEndpoint endpoint) {
    }
}
