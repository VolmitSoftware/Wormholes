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
import net.minecraft.gametest.framework.GameTestHelper;
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
            new MinecraftNexusMenus(runtime).open(actor.player(), source, 0);
            assertThat(actor.player().containerMenu instanceof MinecraftInventoryMenu, "Native dial menu did not open");
            actor.player().closeContainer();
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
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS nexus network_persistence dial debounce menu per_traveler_actual_traversal return_address redstone reciprocal api_snapshot api_mutation api_resolver_traversal api_lifecycle");
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
