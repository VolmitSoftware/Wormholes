package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.nexus.DestinationEntry;
import art.arcane.wormholes.nexus.DestinationMode;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.SelectionRule;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.BlockPos;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class MinecraftNexusGameTest {
    private final WormholesModRuntime runtime;
    private final GameTestHelper helper;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final List<MinecraftPortal> portals = new ArrayList<>();
    private final long deadline = System.currentTimeMillis() + 20_000L;
    private MinecraftGameTestPlayer actor;
    private PortalNetwork network;
    private PortalNetwork menuNetwork;
    private MinecraftPortal source;
    private MinecraftPortal destination;
    private ArmorStand traveler;
    private BlockPos signal;
    private BlockState previous;
    private int stage;
    private AutoCloseable resolver;
    private AutoCloseable listener;
    private final List<MinecraftWormholesApi.Event> events = new ArrayList<>();

    private MinecraftNexusGameTest(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftNexusGameTest test = new MinecraftNexusGameTest(helper, runtime);
        test.start();
        return test.result;
    }

    private void start() {
        try {
            actor = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "NexusProbe");
            listener = runtime.api().listen(events::add);
            source = portal(2);
            MinecraftPortal projected = portal(8);
            destination = portal(14);
            MinecraftNexus nexus = runtime.nexus();
            network = nexus.create(actor.player(), "probe-" + UUID.randomUUID());
            nexus.join(actor.player(), network, source, "AAAA");
            network = nexus.networks().byId(network.id());
            nexus.join(actor.player(), network, projected, "BBBB");
            network = nexus.networks().byId(network.id());
            nexus.join(actor.player(), network, destination, "CCCC");
            network = nexus.networks().byId(network.id());
            assertThat(nexus.dial(actor.player(), source, "BBBB"), "Native dial was refused");
            assertThat(source.getDestinationId().equals(projected.getId()), "Native dial did not replace destination");
            assertThat(!nexus.dial(actor.player(), source, "CCCC"), "Native dial debounce was bypassed");
            NetworkRegistry loaded = new NetworkRegistry(nexus.networks().directory(), MinecraftJsonDocuments.INSTANCE);
            loaded.load();
            assertThat(loaded.byId(network.id()).members().size() == 3, "Native network members did not persist");
            dialLayout(projected, destination);
            menus();
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void traverse() {
        try {
            MinecraftNexus nexus = runtime.nexus();
            nexus.policy(actor.player(), source, new DestinationPolicy(DestinationMode.PER_PLAYER,
                List.of(new DestinationEntry(DestinationEntry.TargetKind.ADDRESS, "CCCC", 1, 0, 0, "")), SelectionRule.ROUND_ROBIN));
            traveler = helper.spawn(EntityTypes.ARMOR_STAND, new Vec3(3, 2, 3.5));
            traveler.setNoGravity(true);
            runtime.schedule(() -> {
                Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
                traveler.setPos(start);
                traveler.xo = start.x;
                traveler.yo = start.y;
                traveler.zo = start.z;
                traveler.setDeltaMovement(0, 0, 0.5);
                traveler.setPos(start.x, start.y, start.z + 1.5);
                runtime.portals().tick();
            }, 2L);
            tick();
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void tick() {
        try {
            assertThat(System.currentTimeMillis() < deadline, "Native Nexus stage " + stage + " timed out");
            if (stage == 0 && traveler.getX() > helper.absolutePos(new BlockPos(12, 0, 0)).getX()) {
                assertThat(!source.getDestinationId().equals(destination.getId()), "Per-traveler route mutated projected link");
                runtime.nexus().policy(actor.player(), destination, new DestinationPolicy(DestinationMode.RETURN,
                    List.of(new DestinationEntry(DestinationEntry.TargetKind.LOCAL, source.getId().toString(), 1, 0, 0, "")), SelectionRule.ROUND_ROBIN));
                PortalCrossing crossing = new PortalCrossing(destination.getFrame(), destination.getOrigin(), destination.getOrigin(),
                    new GeometryVector(0, 0, 0), new GeometryVector(0, 0, 1), true);
                NetworkMember returned = runtime.nexus().destination(destination, traveler, crossing);
                assertThat(returned != null && returned.portalId().equals(source.getId()), "Return address did not retain actual source");
                runtime.nexus().wire(actor.player(), source, new FrameIo(0, -2, 0, FrameIo.RedstoneAction.LOCK, FrameIo.ComparatorOutput.NONE));
                BlockPos control = BlockPos.containing(source.getOrigin().x(), source.getOrigin().y(), source.getOrigin().z()).below(2);
                signal = control.east();
                previous = helper.getLevel().getBlockState(signal);
                helper.getLevel().setBlockAndUpdate(signal, Blocks.REDSTONE_BLOCK.defaultBlockState());
                stage = 1;
            } else if (stage == 1 && !source.isOutgoingTraversalsEnabled()) {
                runtime.nexus().pair(actor.player(), source, destination, true);
                assertThat(source.getId().equals(destination.getDestinationId()), "Reciprocal native link not created");
                runtime.nexus().pair(actor.player(), source, destination, false);
                assertThat(destination.getDestinationId() == null, "Reciprocal native unlink did not clear return");
                source.setOutgoingTraversalsEnabled(true);
                runtime.nexus().policy(actor.player(), source, DestinationPolicy.empty());
                source.unlink();
                runtime.portals().save(source);
                resolver = runtime.api().registerDestinationResolver((portal, travelerId) -> portal.id().equals(source.getId())
                    ? Optional.of(destination.getId()) : Optional.empty());
                runtime.api().rename(source.getId(), "api-probe").thenAccept(renamed -> assertThat(renamed, "Native API mutation failed"));
                traveler.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3))));
                stage = 2;
                runtime.schedule(() -> {
                    Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
                    traveler.setPos(start);
                    traveler.xo = start.x;
                    traveler.yo = start.y;
                    traveler.zo = start.z;
                    traveler.setDeltaMovement(0, 0, 0.5);
                    traveler.setPos(start.x, start.y, start.z + 1.5);
                    runtime.portals().tick();
                }, 25L);
            } else if (stage == 2 && traveler.getX() > helper.absolutePos(new BlockPos(12, 0, 0)).getX()) {
                assertThat(source.getDestinationId() == null, "API route changed stored destination");
                assertThat(runtime.api().portals().byId(source.getId()).orElseThrow().name().equals("api-probe"), "Native API snapshot did not publish mutation");
                assertThat(events.stream().anyMatch(event -> event.kind() == MinecraftWormholesApi.Kind.PORTAL_CREATED && source.getId().equals(event.portalId())), "Native API create lifecycle missing");
                assertThat(events.stream().anyMatch(event -> event.kind() == MinecraftWormholesApi.Kind.HANDOFF_COMPLETED && traveler.getUUID().equals(event.travelerId())), "Native API arrival lifecycle missing");
                finish(null);
                return;
            }
            runtime.schedule(this::tick, 1L);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void dialLayout(MinecraftPortal projected, MinecraftPortal destination) {
        ServerPlayer viewer = actor.player();
        runtime.nexus().menus().open(viewer, source);
        MinecraftSubsystemMenuProbe.assertWindow(helper, viewer, MinecraftPortalText.router(runtime, source, true), 6,
            Items.STAINED_GLASS_PANE.cyan(), MinecraftSubsystemMenuProbe.slot(-4, 5), "Dial window");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -4, 0, Items.ENDER_PEARL, dialEntry(viewer, "BBBB", projected), true, "Dialed address");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -3, 0, Items.ENDER_PEARL, dialEntry(viewer, "CCCC", destination), false, "Dial address");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 0, 5, Items.PAPER, MinecraftSubsystemMenuProbe.name(viewer,
            WormholesMessages.PORTAL_MENU_DESTINATION_PAGE, MinecraftPortalText.arguments("page", 1, "pages", 1, "count", 2)), false, "Dial page");
        viewer.closeContainer();
    }

    private String dialEntry(ServerPlayer viewer, String address, MinecraftPortal portal) {
        return MinecraftSubsystemMenuProbe.name(viewer, NexusMessages.MENU_DIAL_ENTRY, MinecraftPortalText.arguments("address", address,
            "portal", portal.getName(), "world", portal.getWorldKey(), "state",
            MinecraftSubsystemMenuProbe.text(viewer, WormholesMessages.LABEL_OPEN, MessageArgs.empty())));
    }

    private void menus() {
        ServerPlayer viewer = actor.player();
        MinecraftPortal menuSource = portal(20);
        MinecraftPortal menuPeer = portal(26);
        String name = "menu-" + UUID.randomUUID();
        MinecraftNetworkMenuEntry entry = new MinecraftNetworkMenuEntry(runtime);
        GameTestSequence sequence = helper.startSequence();
        step(sequence, () -> {
            assertThat(entry.id().equals("nexus-network") && entry.icon() == Items.COMPASS && entry.label() == NexusMessages.MENU_ENTRY,
                "Network entry identity differs from Bukkit");
            assertThat(!entry.enchanted(menuSource, viewer), "Network entry glowed off network");
            entry.onLeftClick(menuSource, viewer, new MinecraftWindow(runtime, viewer));
            assertJoinLayout(viewer, menuSource);
            MinecraftSubsystemMenuProbe.left(viewer, 1, 1);
        });
        idle(sequence, 3, () -> {
            MinecraftSubsystemMenuProbe.assertClosed(helper, viewer, "Network create prompt");
            assertThat(MinecraftChatInput.chat(viewer, name), "Network create prompt did not consume chat");
        });
        idle(sequence, 2, () -> {
            menuNetwork = runtime.nexus().networks().byName(name);
            assertThat(menuNetwork != null && menuNetwork.member(menuSource.getId()) != null, "Network create prompt did not create and join");
            assertThat(entry.enchanted(menuSource, viewer), "Network entry did not glow on a network");
            assertThat(MinecraftSubsystemMenuProbe.messaged(actor.messages(), MinecraftSubsystemMenuProbe.text(viewer, NexusMessages.CREATED,
                MinecraftPortalText.arguments("name", name))), "Network create notice was not sent");
            MinecraftSubsystemMenuProbe.assertClosed(helper, viewer, "Network create completion");
            runtime.nexus().menus().openManagement(viewer, menuSource);
            assertNetworkLayout(viewer, menuSource, "false", "SINGLE", "NONE/NONE");
            MinecraftSubsystemMenuProbe.left(viewer, -3, 2);
        });
        idle(sequence, 3, () -> {
            assertThat(Boolean.TRUE.equals(menuSource.setting("nexus.reciprocal")), "Reciprocal toggle did not persist");
            assertNetworkLayout(viewer, menuSource, "true", "SINGLE", "NONE/NONE");
            MinecraftSubsystemMenuProbe.left(viewer, -1, 2);
        });
        idle(sequence, 3, () -> {
            assertNetworkLayout(viewer, menuSource, "true", DestinationMode.SINGLE.next().name(), "NONE/NONE");
            MinecraftSubsystemMenuProbe.right(viewer, -1, 2);
        });
        idle(sequence, 2, () -> {
            assertNetworkLayout(viewer, menuSource, "true", "SINGLE", "NONE/NONE");
            MinecraftSubsystemMenuProbe.right(viewer, 1, 2);
        });
        idle(sequence, 2, () -> {
            assertNetworkLayout(viewer, menuSource, "true", "SINGLE", "NONE/" + FrameIo.ComparatorOutput.NONE.next().name());
            MinecraftSubsystemMenuProbe.left(viewer, -1, 1);
        });
        idle(sequence, 3, () -> {
            assertThat(runtime.nexus().networks().byId(menuNetwork.id()).visibility() == menuNetwork.visibility().next(),
                "Network visibility click did not cycle");
            MinecraftSubsystemMenuProbe.left(viewer, -3, 1);
        });
        idle(sequence, 3, () -> {
            MinecraftSubsystemMenuProbe.assertClosed(helper, viewer, "Network address prompt");
            assertThat(MinecraftChatInput.chat(viewer, "ZZZZ"), "Network address prompt did not consume chat");
        });
        idle(sequence, 2, () -> {
            assertThat("ZZZZ".equals(menuSource.setting("nexus.address")), "Network address prompt did not apply");
            assertThat(MinecraftSubsystemMenuProbe.messaged(actor.messages(), MinecraftSubsystemMenuProbe.text(viewer, NexusMessages.ADDRESS_SET,
                MinecraftPortalText.arguments("portal", menuSource.getName(), "address", "ZZZZ"))), "Network address notice was not sent");
            runtime.nexus().menus().openManagement(viewer, menuPeer);
            MinecraftSubsystemMenuProbe.left(viewer, -1, 1);
        });
        idle(sequence, 3, () -> assertThat(MinecraftChatInput.chat(viewer, name), "Network join prompt did not consume chat"));
        idle(sequence, 2, () -> {
            assertThat(runtime.nexus().networks().byName(name).member(menuPeer.getId()) != null, "Network join prompt did not join");
            runtime.nexus().menus().openManagement(viewer, menuSource);
            MinecraftSubsystemMenuProbe.left(viewer, 3, 1);
        });
        idle(sequence, 3, () -> {
            MinecraftSubsystemMenuProbe.assertWindow(helper, viewer, MinecraftPortalText.router(runtime, menuSource, true), 6,
                Items.STAINED_GLASS_PANE.cyan(), MinecraftSubsystemMenuProbe.slot(4, 4), "Network dial window");
            String peerAddress = String.valueOf(menuPeer.setting("nexus.address"));
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -4, 0, Items.ENDER_PEARL, dialEntry(viewer, peerAddress, menuPeer), false,
                "Network dial entry");
            MinecraftSubsystemMenuProbe.left(viewer, -4, 0);
        });
        idle(sequence, 3, () -> {
            MinecraftSubsystemMenuProbe.assertClosed(helper, viewer, "Network dial");
            assertThat(menuPeer.getId().equals(menuSource.getDestinationId()), "Network dial click did not link the portal");
            runtime.nexus().menus().openManagement(viewer, menuSource);
            MinecraftSubsystemMenuProbe.left(viewer, 3, 2);
        });
        idle(sequence, 3, () -> {
            assertThat(menuSource.setting("nexus.networkId") == null, "Network leave did not clear the portal");
            assertJoinLayout(viewer, menuSource);
            viewer.closeContainer();
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS nexus_menus dial_layout network_layout create_prompt join_prompt reciprocal policy redstone visibility address_prompt dial_click leave");
            traverse();
        });
    }

    private void assertJoinLayout(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftSubsystemMenuProbe.assertWindow(helper, viewer, MinecraftPortalText.router(runtime, portal, true), 4,
            Items.STAINED_GLASS_PANE.cyan(), MinecraftSubsystemMenuProbe.slot(-3, 1), "Network join window");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 0, 0, Items.COMPASS, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_PLACARD, MinecraftPortalText.arguments("name", "", "address", "", "count", 0)), false, "Network placard");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -1, 1, Items.ENDER_EYE,
            MinecraftSubsystemMenuProbe.name(viewer, NexusMessages.MENU_JOIN, MessageArgs.empty()), false, "Network join");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 1, 1, Items.NETHER_STAR,
            MinecraftSubsystemMenuProbe.name(viewer, NexusMessages.MENU_CREATE, MessageArgs.empty()), true, "Network create");
    }

    private void assertNetworkLayout(ServerPlayer viewer, MinecraftPortal portal, String reciprocal, String policy, String redstone) {
        PortalNetwork network = runtime.nexus().networks().byId(menuNetwork.id());
        String address = String.valueOf(portal.setting("nexus.address"));
        MinecraftSubsystemMenuProbe.assertWindow(helper, viewer, MinecraftPortalText.router(runtime, portal, true), 4,
            Items.STAINED_GLASS_PANE.cyan(), MinecraftSubsystemMenuProbe.slot(0, 2), "Network window");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 0, 0, Items.COMPASS, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_PLACARD, MinecraftPortalText.arguments("name", network.name(), "address", address, "count",
                network.members().size())), false, "Network placard");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -3, 1, Items.NAME_TAG, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_ADDRESS, MinecraftPortalText.arguments("address", address)), false, "Network address");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -1, 1, Items.ITEM_FRAME, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_VISIBILITY, MinecraftPortalText.arguments("value", network.visibility().name())), false, "Network visibility");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 1, 1, Items.IRON_BARS, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_TOPOLOGY, MinecraftPortalText.arguments("value", network.topology().name())), false, "Network topology");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 3, 1, Items.LEVER,
            MinecraftSubsystemMenuProbe.name(viewer, NexusMessages.MENU_DIAL, MessageArgs.empty()), false, "Network dial");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -3, 2, Items.ENDER_CHEST, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_RECIPROCAL, MinecraftPortalText.arguments("state", reciprocal)), false, "Network reciprocal");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -1, 2, Items.TARGET, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_POLICY, MinecraftPortalText.arguments("mode", policy, "count", 0)), false, "Network policy");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 1, 2, Items.REDSTONE_TORCH, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_REDSTONE, MinecraftPortalText.arguments("value", redstone)), false, "Network redstone");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 3, 2, Items.BARRIER, MinecraftSubsystemMenuProbe.name(viewer,
            NexusMessages.MENU_LEAVE, MinecraftPortalText.arguments("name", network.name())), false, "Network leave");
    }

    private void step(GameTestSequence sequence, Runnable body) {
        sequence.thenExecute(() -> guarded(body));
    }

    private void idle(GameTestSequence sequence, int ticks, Runnable body) {
        sequence.thenIdle(ticks).thenExecute(() -> guarded(body));
    }

    private void guarded(Runnable body) {
        if (result.isDone()) {
            return;
        }
        try {
            body.run();
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private MinecraftPortal portal(int x) {
        BlockPos position = helper.absolutePos(new BlockPos(x, 2, 4));
        MinecraftPortal portal = runtime.portals().create(actor.player().getUUID(), helper.getLevel(),
            List.of(position, position.above(), position.east(), position.east().above()), PortalType.PORTAL, new Vec3(0, 0, -1));
        portals.add(portal);
        return portal;
    }

    private void finish(Throwable failure) {
        if (result.isDone()) {
            return;
        }
        try {
            if (resolver != null) { resolver.close(); }
            if (listener != null) { listener.close(); }
            if (traveler != null) {
                traveler.discard();
            }
            if (signal != null) {
                helper.getLevel().setBlockAndUpdate(signal, previous);
            }
            if (network != null) {
                runtime.nexus().delete(actor.player(), runtime.nexus().networks().byId(network.id()));
            }
            if (menuNetwork != null && runtime.nexus().networks().byId(menuNetwork.id()) != null) {
                runtime.nexus().delete(actor.player(), runtime.nexus().networks().byId(menuNetwork.id()));
            }
            for (MinecraftPortal portal : portals) {
                runtime.portals().remove(portal.getId());
            }
            if (actor != null) {
                actor.close();
            }
        } catch (Throwable cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure == null) {
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS nexus network_persistence dial debounce dial_menu network_menu per_traveler_actual_traversal return_address redstone reciprocal api_snapshot api_mutation api_resolver_traversal api_lifecycle");
            result.complete(true);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
