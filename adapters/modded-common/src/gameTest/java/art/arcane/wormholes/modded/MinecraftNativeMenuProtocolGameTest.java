package art.arcane.wormholes.modded;

import art.arcane.wormholes.WormholesBuild;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuCapability;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.modded.menu.MenuPayload;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class MinecraftNativeMenuProtocolGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final byte[] MALFORMED = {(byte) 0xEE, 1, 2};
    private static final long SENDER_JOIN_MILLIS = 5000L;
    private static final String PROBE = "MenuProbe";
    private static final String SILENT = "MenuSilent";
    private static final String CHEST = "MenuChest";

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final List<String> checks = new ArrayList<>();
    private MinecraftGameTestPlayer probe;
    private MinecraftGameTestPlayer silent;
    private MinecraftGameTestPlayer stale;
    private MinecraftGameTestPlayer chest;
    private MinecraftPortal portal;
    private long revision;

    private MinecraftNativeMenuProtocolGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
    }

    public static void run(GameTestHelper helper) {
        new MinecraftNativeMenuProtocolGameTest(helper).start();
    }

    private void start() {
        helper.startSequence()
            .thenExecute(this::connect)
            .thenIdle(2)
            .thenExecute(this::framesWithoutHelloAreDropped)
            .thenIdle(1)
            .thenExecute(this::offThreadHelloRunsOnTheServerThread)
            .thenIdle(1)
            .thenExecute(this::helloIsAccepted)
            .thenIdle(1)
            .thenExecute(this::joinClearsAStaleCapability)
            .thenIdle(1)
            .thenExecute(this::staleReleaseKeepsTheNewerCapability)
            .thenIdle(1)
            .thenExecute(this::openHomeForTheProbe)
            .thenIdle(2)
            .thenExecute(this::homeOpensAsANativeScreen)
            .thenIdle(2)
            .thenExecute(this::actionsUpdateTheNativeScreen)
            .thenIdle(2)
            .thenExecute(this::serverboundFramesAreCappedBeforeCopying)
            .thenIdle(1)
            .thenExecute(this::violationsBelowTheLimitKeepTheSession)
            .thenIdle(1)
            .thenExecute(this::thirdViolationResets)
            .thenIdle(2)
            .thenExecute(this::reconnectStartsFresh)
            .thenIdle(1)
            .thenExecute(this::openHomeWithoutHello)
            .thenIdle(2)
            .thenExecute(this::homeWithoutHelloOpensAChest)
            .thenIdle(1)
            .thenExecute(this::report)
            .thenSucceed();
    }

    private void connect() {
        probe = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), PROBE);
        silent = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), SILENT);
        chest = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), CHEST);
        probe.drainPackets();
        silent.drainPackets();
        chest.drainPackets();
    }

    private void framesWithoutHelloAreDropped() {
        send(silent, encode(new MenuClientMessage.Back(0L)));
        send(silent, encode(new MenuClientMessage.Action(new MenuAction(0L, "projection", MenuInteraction.PRIMARY, null))));
        send(silent, MALFORMED);
        send(silent, MALFORMED);
        send(silent, MALFORMED);
        require(frames(silent).isEmpty(), "frames from a player without HELLO produced menu frames");
        require(capability(silent).isEmpty(), "a player without HELLO gained a menu capability");
        sendOffThread(silent, encode(new MenuClientMessage.Hello(7)));
        require(capability(silent).isEmpty(), "a HELLO handled off the server thread was not rescheduled");
        checks.add("no-hello-dropped");
    }

    private void offThreadHelloRunsOnTheServerThread() {
        require(acceptedOnce(frames(silent)), "rescheduled HELLO 7 was not answered with ACCEPT 1");
        require(capability(silent).equals(Optional.of(new MenuCapability(1))), "rescheduled HELLO 7 did not negotiate version 1");
        checks.add("offthread-reschedule");
    }

    private void helloIsAccepted() {
        send(probe, encode(new MenuClientMessage.Hello(1)));
        require(acceptedOnce(frames(probe)), "HELLO 1 was not answered with ACCEPT 1");
        require(capability(probe).equals(Optional.of(new MenuCapability(1))), "HELLO 1 did not store capability version 1");
        send(probe, encode(new MenuClientMessage.Hello(1)));
        require(acceptedOnce(frames(probe)), "a repeated HELLO did not re-send ACCEPT");
        checks.add("hello-accept");
    }

    private void joinClearsAStaleCapability() {
        stale = probe;
        UUID probeId = stale.player().getUUID();
        stale.leavePlayerList();
        require(capability(stale).isPresent(), "leaving the player list before the disconnect callback cleared the capability");
        probe = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), PROBE);
        probe.drainPackets();
        require(probe.player().getUUID().equals(probeId), "the reconnecting probe changed identity");
        require(capability(probe).isEmpty(), "joining kept the stale connection's menu capability");
        send(probe, encode(new MenuClientMessage.Hello(1)));
        require(acceptedOnce(frames(probe)), "HELLO after the join was not accepted");
        checks.add("join-clears");
    }

    private void staleReleaseKeepsTheNewerCapability() {
        runtime.playerDisconnected(stale.player());
        stale.channel().finishAndReleaseAll();
        require(capability(probe).equals(Optional.of(new MenuCapability(1))), "the stale connection's late release cleared the new capability");
        checks.add("stale-release-kept");
    }

    private void openHomeForTheProbe() {
        portal = createPortal(probe);
        runtime.menus().open(probe.player().getUUID(), PortalRoutes.home(portal.getId()));
    }

    private void homeOpensAsANativeScreen() {
        List<MenuServerMessage> opened = frames(probe);
        require(opened.size() == 1 && opened.getFirst() instanceof MenuServerMessage.Open, "portal home did not open as one native OPEN frame: " + opened);
        MenuScreen screen = ((MenuServerMessage.Open) opened.getFirst()).screen();
        require(screen.screenId().equals(PortalRoutes.HOME), "native OPEN carried " + screen.screenId() + " instead of the portal home");
        require(projections(screen), "native portal home did not show projections on");
        require(probe.player().containerMenu == probe.player().inventoryMenu, "a native viewer also received a chest window");
        revision = screen.revision();
        send(probe, encode(new MenuClientMessage.Action(new MenuAction(revision, "toggle-projections", MenuInteraction.PRIMARY, null))));
        checks.add("native-open");
    }

    private void actionsUpdateTheNativeScreen() {
        List<MenuServerMessage> updated = frames(probe);
        require(!updated.isEmpty() && updated.getFirst() instanceof MenuServerMessage.Update, "native ACTION did not answer with an UPDATE frame: " + updated);
        require(updated.stream().skip(1).allMatch(MenuServerMessage.Feedback.class::isInstance), "native ACTION answered more than its UPDATE and feedback");
        MenuScreen screen = ((MenuServerMessage.Update) updated.getFirst()).screen();
        require(screen.revision() > revision, "native UPDATE did not advance the revision");
        require(!projections(screen) && portal.getProjectionMode() == ProjectionMode.OFF, "native projection toggle did not reach the portal");
        send(probe, encode(new MenuClientMessage.Closed(screen.revision())));
        runtime.portals().remove(portal.getId());
        checks.add("native-action-update");
    }

    private void serverboundFramesAreCappedBeforeCopying() {
        require(serverboundDecodes(MenuLimits.MAX_C2S_BYTES), "the serverbound menu codec refused a frame at the client limit");
        require(!serverboundDecodes(MenuLimits.MAX_C2S_BYTES + 1), "the serverbound menu codec accepted a frame over the client limit");
        checks.add("c2s-cap");
    }

    private void violationsBelowTheLimitKeepTheSession() {
        send(probe, MALFORMED);
        send(probe, MALFORMED);
        require(frames(probe).isEmpty(), "violations below the limit produced menu frames");
        require(capability(probe).isPresent(), "two violations reset the menu session");
        send(probe, encode(new MenuClientMessage.Back(0L)));
        require(capability(probe).isPresent(), "a valid frame after two violations reset the menu session");
        checks.add("violations-below-limit");
    }

    private void thirdViolationResets() {
        send(probe, MALFORMED);
        require(capability(probe).isEmpty(), "the third violation did not reset the menu session");
        send(probe, encode(new MenuClientMessage.Back(0L)));
        require(frames(probe).isEmpty(), "frames after a reset produced menu frames");
        send(probe, encode(new MenuClientMessage.Hello(1)));
        require(frames(probe).isEmpty(), "HELLO after a reset was answered on the same connection");
        require(capability(probe).isEmpty(), "HELLO after a reset restored the menu capability");
        checks.add("third-violation-reset");
    }

    private void reconnectStartsFresh() {
        probe.close();
        probe = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), PROBE);
        probe.drainPackets();
        require(capability(probe).isEmpty(), "a reconnect kept the previous connection's menu capability");
        send(probe, encode(new MenuClientMessage.Hello(1)));
        require(acceptedOnce(frames(probe)), "HELLO on a fresh connection was not accepted");
        silent.close();
        require(capability(silent).isEmpty(), "disconnect did not clear the menu capability");
        checks.add("reconnect-fresh");
    }

    private void openHomeWithoutHello() {
        portal = createPortal(chest);
        runtime.menus().open(chest.player().getUUID(), PortalRoutes.home(portal.getId()));
    }

    private void homeWithoutHelloOpensAChest() {
        List<MenuServerMessage> sent = frames(chest);
        require(sent.isEmpty(), "portal home sent native frames to a player who never sent HELLO: " + sent);
        require(capability(chest).isEmpty(), "a player who never sent HELLO gained a menu capability");
        require(chest.player().containerMenu != chest.player().inventoryMenu, "portal home did not open a chest window without HELLO");
        chest.player().closeContainer();
        runtime.portals().remove(portal.getId());
        chest.close();
        checks.add("no-hello-chest");
    }

    private MinecraftPortal createPortal(MinecraftGameTestPlayer owner) {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            cells.add(helper.absolutePos(new BlockPos(3, y, 1)));
            cells.add(helper.absolutePos(new BlockPos(4, y, 1)));
        }
        return runtime.portals().create(owner.player().getUUID(), helper.getLevel(), cells, PortalType.PORTAL, new Vec3(0, 0, -1));
    }

    private void report() {
        LOGGER.info("WORMHOLES_GAME_TEST_PASS native_menu_protocol_runtime checks={}", String.join(",", checks));
    }

    private static boolean projections(MenuScreen screen) {
        return screen.element("toggle-projections").orElseThrow() instanceof ToggleElement toggle && toggle.value();
    }

    private Optional<MenuCapability> capability(MinecraftGameTestPlayer connection) {
        return runtime.menus().capabilities().capability(connection.player().getUUID());
    }

    private void require(boolean condition, String failure) {
        if (!condition) {
            LOGGER.error("WORMHOLES_NATIVE_MENU_PROTOCOL FAIL {}", failure);
        }
        helper.assertTrue(condition, failure);
    }

    private static void send(MinecraftGameTestPlayer connection, byte[] frame) {
        new ServerboundCustomPayloadPacket(new MenuPayload(frame)).handle(connection.player().connection);
    }

    private static void sendOffThread(MinecraftGameTestPlayer connection, byte[] frame) {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try {
                send(connection, frame);
            } catch (RuntimeException exception) {
                failure.set(exception);
            }
        }, "wormholes-menu-offthread");
        sender.start();
        try {
            sender.join(SENDER_JOIN_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sending a menu frame off the server thread", exception);
        }
        if (sender.isAlive()) {
            throw new IllegalStateException("off-thread menu frame did not return within " + SENDER_JOIN_MILLIS + " ms");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("off-thread menu frame failed", failure.get());
        }
    }

    private static boolean serverboundDecodes(int bodyBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeZero(bodyBytes);
            return MenuPayload.SERVERBOUND_CODEC.decode(buffer).data().length == bodyBytes;
        } catch (IllegalArgumentException refused) {
            return false;
        } finally {
            buffer.release();
        }
    }

    private static boolean acceptedOnce(List<MenuServerMessage> messages) {
        return messages.size() == 1 && messages.getFirst() instanceof MenuServerMessage.Accept accept && accept.version() == 1
            && accept.server().wormholesVersion().equals(WormholesBuild.version());
    }

    private static List<MenuServerMessage> frames(MinecraftGameTestPlayer connection) {
        List<MenuServerMessage> messages = new ArrayList<>();
        for (Object packet : connection.drainPackets()) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof MenuPayload payload) {
                messages.add(decode(payload.data()));
            }
        }
        return messages;
    }

    private static byte[] encode(MenuClientMessage message) {
        try {
            return MenuCodec.encodeClient(message);
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException("menu test frame could not be encoded", exception);
        }
    }

    private static MenuServerMessage decode(byte[] frame) {
        try {
            return MenuCodec.decodeServer(frame);
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException("server menu frame could not be decoded", exception);
        }
    }
}
