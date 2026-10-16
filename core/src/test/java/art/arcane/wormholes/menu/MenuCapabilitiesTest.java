package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class MenuCapabilitiesTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private FakeMenuHost host;
    private MenuService service;
    private long now;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Tester");
        now = 5_000L;
        service = new MenuService(host, new MenuRegistry(List.of(new TestController())), () -> now);
    }

    @Test
    void viewersWithoutHelloGetChests() {
        service.open(VIEWER, TestController.main());

        assertEquals(List.of("open:test.main@1"), host.chest.events);
        assertTrue(host.frames.isEmpty());
        assertEquals(MenuRendererChoice.CHEST, MenuRendererChoice.choose(Optional.empty(), true));
    }

    @Test
    void helloZeroIsRefusedAndStoresNothing() throws MenuProtocolException {
        hello(1);
        service.quit(VIEWER);
        hello(0);

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertEquals(1, host.frames.size());
        assertTrue(MenuNegotiation.accept(0).isEmpty());
        assertTrue(MenuNegotiation.accept(-4).isEmpty());
        service.open(VIEWER, TestController.main());
        assertEquals("open:test.main@1", host.chest.lastEvent());
    }

    @Test
    void newerClientsSettleOnTheServerVersionAndRepeatedHellosResend() throws MenuProtocolException {
        hello(2);
        hello(2);

        assertEquals(new MenuCapability(MenuProtocol.VERSION), service.capabilities().capability(VIEWER).orElseThrow());
        assertEquals(List.of(new MenuServerMessage.Accept(1), new MenuServerMessage.Accept(1)), host.serverMessages());
    }

    @Test
    void capableViewersGetNativeScreens() throws MenuProtocolException {
        hello(1);

        service.open(VIEWER, TestController.main());

        assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertTrue(host.chest.events.isEmpty());
    }

    @Test
    void disabledNativeScreensFallBackToChests() throws MenuProtocolException {
        hello(1);
        host.nativeEnabled = false;

        service.open(VIEWER, TestController.main());

        assertEquals(List.of("open:test.main@1"), host.chest.events);
        assertEquals(MenuRendererChoice.CHEST, MenuRendererChoice.choose(Optional.of(new MenuCapability(1)), false));
    }

    @Test
    void encodeFailuresFallBackToChestsAndKeepTheCapability() throws MenuProtocolException {
        TestController oversized = new TestController() {
            @Override
            public MenuBuild build(MenuContext context) {
                MenuBuild build = super.build(context);
                List<String> description = new ArrayList<>(MenuLimits.MAX_DESCRIPTION_LINES);
                for (int line = 0; line < MenuLimits.MAX_DESCRIPTION_LINES; line++) {
                    description.add("d".repeat(MenuLimits.MAX_STRING_BYTES));
                }
                List<MenuElement> elements = new ArrayList<>(MenuLimits.MAX_ELEMENTS);
                for (int index = 0; index < MenuLimits.MAX_ELEMENTS; index++) {
                    elements.add(new LabelElement(MenuElementCommon.builder("label-" + index).description(description).build()));
                }
                return build.withScreen(build.screen().toBuilder().elements(elements).build());
            }
        };
        MenuService failing = new MenuService(host, new MenuRegistry(List.of(oversized)), () -> now);
        failing.receive(VIEWER, MenuCodec.encodeClient(new MenuClientMessage.Hello(1)));

        failing.open(VIEWER, TestController.main());

        assertEquals("open:test.main@1", host.chest.lastEvent());
        assertTrue(failing.capabilities().capability(VIEWER).isPresent());
        assertEquals(List.of("open:test.main@1"), host.chest.events);
        assertEquals(List.of(new MenuServerMessage.Accept(1)), host.serverMessages());
    }

    @Test
    void unregisteredChannelsFallBackToChests() throws MenuProtocolException {
        hello(1);
        host.transportOpen = false;

        service.open(VIEWER, TestController.main());

        assertEquals("open:test.main@1", host.chest.lastEvent());
        assertTrue(service.capabilities().capability(VIEWER).isPresent());
    }

    @Test
    void quitClearsTheCapability() throws MenuProtocolException {
        hello(1);
        service.open(VIEWER, TestController.main());

        service.quit(VIEWER);

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertFalse(service.chestPrecheck(VIEWER, "increment", MenuInteraction.PRIMARY));
    }

    @Test
    void protocolResetsClearTheCapability() throws MenuProtocolException {
        hello(1);
        service.open(VIEWER, TestController.main());

        service.receive(VIEWER, new byte[] {(byte) 0xEE});
        service.receive(VIEWER, new byte[] {(byte) 0xEE});
        service.receive(VIEWER, new byte[0]);

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
        service.open(VIEWER, TestController.main());
        assertEquals("open:test.main@2", host.chest.lastEvent());
    }

    @Test
    void helloAfterAResetIsIgnoredUntilQuit() throws MenuProtocolException {
        hello(1);
        service.open(VIEWER, TestController.main());
        service.receive(VIEWER, new byte[] {(byte) 0xEE});
        service.receive(VIEWER, new byte[] {(byte) 0xEE});
        service.receive(VIEWER, new byte[] {(byte) 0xEE});
        int frames = host.frames.size();

        byte[] helloFrame = MenuCodec.encodeClient(new MenuClientMessage.Hello(1));
        byte[] backFrame = MenuCodec.encodeClient(new MenuClientMessage.Back(1L));
        try (MockedStatic<MenuCodec> codec = Mockito.mockStatic(MenuCodec.class, Mockito.CALLS_REAL_METHODS)) {
            service.receive(VIEWER, helloFrame);
            service.receive(VIEWER, backFrame);

            codec.verify(() -> MenuCodec.decodeClient(ArgumentMatchers.any()), Mockito.never());
        }

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertEquals(frames, host.frames.size());
        service.open(VIEWER, TestController.main());
        assertEquals(frames, host.frames.size());
        assertTrue(host.chest.lastEvent().startsWith("open:test.main@"));

        service.quit(VIEWER);
        hello(1);
        assertTrue(service.capabilities().capability(VIEWER).isPresent());
    }

    @Test
    void resetsLandingDuringAHelloLeaveNoCapability() throws MenuProtocolException {
        AtomicBoolean resetOnClock = new AtomicBoolean();
        service = new MenuService(host, new MenuRegistry(List.of(new TestController())), () -> {
            if (resetOnClock.compareAndSet(true, false)) {
                forceReset();
            }
            return now;
        });
        hello(1);
        resetOnClock.set(true);

        hello(1);

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertEquals(List.of(new MenuServerMessage.Accept(1)), host.serverMessages());
        assertEquals(1, host.warnings.size());
        service.open(VIEWER, TestController.main());
        assertEquals("open:test.main@1", host.chest.lastEvent());
    }

    @Test
    void framesBeforeAcceptAreIgnored() throws MenuProtocolException {
        service.open(VIEWER, TestController.main());

        service.receive(VIEWER, MenuCodec.encodeClient(new MenuClientMessage.Closed(1L)));

        assertTrue(service.chestPrecheck(VIEWER, "increment", MenuInteraction.PRIMARY));
    }

    @Test
    void oversizedFramesAreDroppedBeforeDecoding() throws MenuProtocolException {
        byte[] oversizedHello = new byte[MenuLimits.MAX_C2S_BYTES + 1];
        oversizedHello[0] = (byte) MenuCodec.C2S_HELLO;
        oversizedHello[1] = (byte) MenuProtocol.VERSION;

        try (MockedStatic<MenuCodec> codec = Mockito.mockStatic(MenuCodec.class, Mockito.CALLS_REAL_METHODS)) {
            service.receive(VIEWER, oversizedHello);
            codec.verify(() -> MenuCodec.decodeClient(ArgumentMatchers.any()), Mockito.never());
            assertTrue(service.capabilities().capability(VIEWER).isEmpty());
            assertTrue(host.frames.isEmpty());

            hello(1);
            codec.clearInvocations();
            service.receive(VIEWER, oversizedHello);

            codec.verify(() -> MenuCodec.decodeClient(ArgumentMatchers.any()), Mockito.never());
        }
        assertEquals(List.of(new MenuServerMessage.Accept(1)), host.serverMessages());
    }

    @Test
    void nonHelloFramesBeforeHelloAreNotDecoded() throws MenuProtocolException {
        byte[] back = MenuCodec.encodeClient(new MenuClientMessage.Back(1L));
        byte[] malformedAction = new byte[] {(byte) MenuCodec.C2S_ACTION, (byte) 0xFF};

        try (MockedStatic<MenuCodec> codec = Mockito.mockStatic(MenuCodec.class, Mockito.CALLS_REAL_METHODS)) {
            service.receive(VIEWER, back);
            service.receive(VIEWER, malformedAction);
            service.receive(VIEWER, new byte[] {(byte) 0xEE});
            service.receive(VIEWER, new byte[0]);

            codec.verify(() -> MenuCodec.decodeClient(ArgumentMatchers.any()), Mockito.never());

            hello(1);

            codec.verify(() -> MenuCodec.decodeClient(ArgumentMatchers.any()), Mockito.times(1));
        }
        assertTrue(service.capabilities().capability(VIEWER).isPresent());
        assertEquals(List.of(new MenuServerMessage.Accept(1)), host.serverMessages());
        assertTrue(host.warnings.isEmpty());
    }

    @Test
    void framesBeforeHelloLogOncePerConnection() throws MenuProtocolException {
        Logger logger = Logger.getLogger(MenuService.class.getName());
        Level previousLevel = logger.getLevel();
        RecordingHandler handler = new RecordingHandler();
        logger.setLevel(Level.FINE);
        logger.addHandler(handler);
        try {
            service.receive(VIEWER, new byte[] {(byte) 0xEE});
            service.receive(VIEWER, new byte[MenuLimits.MAX_C2S_BYTES + 1]);
            service.receive(VIEWER, MenuCodec.encodeClient(new MenuClientMessage.Closed(1L)));
            assertEquals(1, handler.records.size());
            assertEquals(Level.FINE, handler.records.getFirst().getLevel());

            service.quit(VIEWER);
            service.receive(VIEWER, new byte[] {(byte) 0xEE});

            assertEquals(2, handler.records.size());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
        assertTrue(host.warnings.isEmpty());
    }

    @Test
    void capabilityVersionsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new MenuCapability(0));
        assertThrows(IllegalArgumentException.class, () -> new MenuCapability(256));
    }

    private void forceReset() {
        for (int violation = 0; violation < MenuLimits.VIOLATION_LIMIT; violation++) {
            service.violation(VIEWER, "reset during hello");
        }
    }

    private void hello(int version) throws MenuProtocolException {
        service.receive(VIEWER, MenuCodec.encodeClient(new MenuClientMessage.Hello(version)));
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
