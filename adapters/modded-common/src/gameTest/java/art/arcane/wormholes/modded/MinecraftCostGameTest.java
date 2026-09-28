package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.portal.PortalType;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MinecraftCostGameTest {
    private static final int MENU_TICKS = 4;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final ServerPlayer player;
    private final EmbeddedChannel channel;
    private final MinecraftPortal source;
    private final MinecraftPortal destination;
    private final Provider provider;
    private final AutoCloseable registration;
    private boolean cleaned;

    private MinecraftCostGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "cost-traveler"), false);
        player = new ServerPlayer(runtime.server(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(runtime.server(), connection, player, cookie);
        player.initInventoryMenu();
        player.setNoGravity(true);
        source = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(2), PortalType.PORTAL, new Vec3(0, 0, -1));
        destination = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(14), PortalType.PORTAL, new Vec3(0, 0, -1));
        helper.assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "Cost fixture portal link failed");
        provider = new Provider(player.getUUID());
        registration = runtime.costs().register(new MinecraftTravelCosts.Registration(provider, "cost-runtime-test", "Wormholes test", 0, () -> true));
    }

    public static void run(GameTestHelper helper) {
        MinecraftCostGameTest test = new MinecraftCostGameTest(helper);
        try {
            test.start();
        } catch (RuntimeException error) {
            test.cleanup();
            throw error;
        }
    }

    private void start() {
        runtime.schedule(this::cleanup, 1190);
        for (int x = 0; x < 20; x++) {
            for (int z = 1; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        ItemStack selected = new ItemStack(Items.DIAMOND, 20);
        selected.set(DataComponents.CUSTOM_NAME, Component.literal("Exact toll"));
        CompoundTag data = new CompoundTag();
        data.putByte("byte", (byte) 4);
        data.putInt("integer", 7);
        selected.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        player.setItemInHand(InteractionHand.MAIN_HAND, selected);
        helper.assertTrue(runtime.menus().open(player, source.getId()) == 1, "Cost owner could not open editor");
        helper.startSequence()
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> click(15))
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> click(32))
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> {
                costWindow();
                helper.assertTrue(item(13).is(Items.HOPPER), "Vanilla cost mode did not show the hopper");
                click(13);
            })
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> capture(selected))
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> click(23))
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> click(23))
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> {
                helper.assertTrue(((Number) ((Map<?, ?>) source.setting("travelCost")).get("quantity")).intValue() == 3,
                    "Cost quantity controls did not persist changes");
                helper.assertTrue(item(23).is(Items.CHEST) && item(23).getCount() == 3, "Cost quantity element did not show the quantity");
                player.closeContainer();
                helper.getLevel().addNewPlayer(player);
                approach();
            })
            .thenIdle(3)
            .thenExecute(this::cross)
            .thenIdle(15)
            .thenExecute(() -> {
                helper.assertTrue(player.getX() < helper.absolutePos(new BlockPos(10, 0, 0)).getX(), "Denied provider allowed actual portal traversal");
                helper.assertTrue(player.getInventory().getItem(0).getCount() == 20, "Provider denial charged exact items");
                helper.assertTrue(provider.quoted > 0 && provider.reserved == 0, "Denial did not use shared provider pipeline");
                provider.deny = false;
                approach();
            })
            .thenIdle(3)
            .thenExecute(this::cross)
            .thenWaitUntil(() -> helper.assertTrue(player.getX() > helper.absolutePos(new BlockPos(10, 0, 0)).getX(), "Paid portal traversal did not arrive"))
            .thenExecute(() -> {
                helper.assertTrue(player.getInventory().getItem(0).getCount() == 17, "Successful traversal did not debit exactly three matching items");
                helper.assertTrue(provider.reserved == 1 && provider.committed == 1 && provider.refunded == 0,
                    "Successful native traversal did not settle provider exactly once");
                helper.assertTrue(runtime.menus().open(player, source.getId()) == 1, "Cost owner could not reopen editor");
            })
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> click(15))
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> click(32))
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> click(11))
            .thenIdle(MENU_TICKS)
            .thenExecute(() -> {
                helper.assertTrue(source.setting("travelCost") == null, "Free mode did not clear persisted price");
                helper.assertTrue(item(11).is(Items.FEATHER) && item(11).hasFoil(), "Free mode was not marked active");
                channel.runPendingTasks();
                helper.assertTrue(!channel.outboundMessages().isEmpty(), "Cost editor emitted no inventory packets");
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS costs_runtime drop_capture exact_nbt quantity provider_denial paid_traversal commit_once free_mode");
                cleanup();
            })
            .thenSucceed();
    }

    private void capture(ItemStack selected) {
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Item capture did not close the cost window");
        player.drop(false);
        helper.assertTrue(player.getMainHandItem().getCount() == 20, "Drop capture removed the selected item");
        helper.assertTrue(source.setting("travelCost") instanceof Map<?, ?>, "Drop capture did not install exact item price");
        Map<?, ?> price = (Map<?, ?>) source.setting("travelCost");
        ItemStack stored = MinecraftItemEncoding.decode((String) price.get("item"), runtime.server().registryAccess());
        helper.assertTrue(ItemStack.isSameItemSameComponents(selected, stored) && stored.getCount() == 1,
            "Drop capture changed exact item components or stored stack count");
        costWindow();
        helper.assertTrue(item(13).is(Items.DIAMOND) && item(13).hasFoil(), "Vanilla cost mode was not marked active");
        helper.assertTrue(item(21).is(Items.DIAMOND) && item(23).is(Items.CHEST) && item(23).getCount() == 1, "Captured item details were not shown");
    }

    private void costWindow() {
        helper.assertTrue(player.containerMenu instanceof MinecraftWindowMenu menu && menu.getRowCount() == 4, "Cost window was not a four-row window");
        helper.assertTrue(item(0).is(Items.STAINED_GLASS_PANE.brown()), "Cost window did not use the brown pane");
        helper.assertTrue(item(4).is(Items.CHEST) && item(31).is(Items.ARROW), "Cost window placard or back control missing");
    }

    private ItemStack item(int slot) {
        return player.containerMenu.getSlot(slot).getItem();
    }

    private void click(int slot) {
        helper.assertTrue(player.containerMenu instanceof MinecraftWindowMenu, "Cost editor window was not open");
        player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, player);
    }

    private void approach() {
        Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        player.setPos(start);
        player.xo = start.x;
        player.yo = start.y;
        player.zo = start.z;
        player.setDeltaMovement(Vec3.ZERO);
    }

    private void cross() {
        Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        player.xo = start.x;
        player.yo = start.y;
        player.zo = start.z;
        player.setPos(start.x, start.y, start.z + 1.5);
    }

    private List<BlockPos> cells(int x) {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            cells.add(helper.absolutePos(new BlockPos(x, y, 4)));
            cells.add(helper.absolutePos(new BlockPos(x + 1, y, 4)));
        }
        return cells;
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        player.closeContainer();
        runtime.costs().disconnected(player);
        runtime.portals().remove(player, source.getId());
        runtime.portals().remove(player, destination.getId());
        player.discard();
        channel.finishAndReleaseAll();
        try {
            registration.close();
        } catch (Exception error) {
            throw new IllegalStateException("Could not close cost test provider", error);
        }
    }

    private static final class Provider implements MinecraftTraversalCostProvider {
        private final UUID travelerId;
        private boolean deny = true;
        private int quoted;
        private int reserved;
        private int committed;
        private int refunded;

        private Provider(UUID travelerId) {
            this.travelerId = travelerId;
        }

        @Override
        public TraversalQuote quote(MinecraftTraversalContext context) {
            if (!travelerId.equals(context.travelerId())) {
                return TraversalQuote.pass();
            }
            quoted++;
            return deny ? TraversalQuote.denied("Test admission denied") : TraversalQuote.payable("Test admission");
        }

        @Override
        public TraversalReservation reserve(MinecraftTraversalContext context, TraversalQuote quote) {
            reserved++;
            return TraversalReservation.reserved(TraversalReceipt.of("cost-runtime"));
        }

        @Override
        public void commit(TraversalReceipt receipt) {
            committed++;
        }

        @Override
        public void refund(TraversalReceipt receipt, TraversalRefundReason reason) {
            refunded++;
        }
    }
}
