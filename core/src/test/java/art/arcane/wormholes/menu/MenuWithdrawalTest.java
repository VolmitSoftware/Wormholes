package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class MenuWithdrawalTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000007");

    private FakeMenuHost host;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Tester");
        service = new MenuService(host, new MenuRegistry(List.of(new TestController())), () -> 5_000L);
    }

    @Test
    void helloZeroReopensTheOpenNativeScreenAsAChest() throws MenuProtocolException {
        hello(1);
        service.open(VIEWER, TestController.main());
        assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        int frames = host.frames.size();

        hello(0);

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertEquals(frames, host.frames.size());
        assertEquals(1, host.chest.events.size());
        assertTrue(host.chest.lastEvent().startsWith("open:test.main@"));
        assertEquals(1, host.warnings.size());
    }

    @Test
    void withdrawnViewersStayOnChestsUntilTheyQuit() throws MenuProtocolException {
        hello(1);
        hello(0);

        hello(1);
        service.open(VIEWER, TestController.main());

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertEquals(List.of(new MenuServerMessage.Accept(1)), host.serverMessages());
        assertTrue(host.chest.lastEvent().startsWith("open:test.main@"));
        service.quit(VIEWER);
        hello(1);
        assertTrue(service.capabilities().capability(VIEWER).isPresent());
    }

    @Test
    void helloZeroWithoutAnOpenScreenOnlyWithdraws() throws MenuProtocolException {
        hello(1);

        hello(0);

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertTrue(host.chest.events.isEmpty());
        assertEquals(1, host.warnings.size());
    }

    @Test
    void helloZeroBeforeAnAcceptedHelloChangesNothing() throws MenuProtocolException {
        hello(0);

        assertTrue(service.capabilities().capability(VIEWER).isEmpty());
        assertTrue(host.frames.isEmpty());
        assertTrue(host.warnings.isEmpty());
        hello(1);
        assertTrue(service.capabilities().capability(VIEWER).isPresent());
    }

    @Test
    void helloZeroLeavesAChestSessionAlone() throws MenuProtocolException {
        service.open(VIEWER, TestController.main());
        hello(1);

        hello(0);

        assertEquals(List.of("open:test.main@1"), host.chest.events);
        assertTrue(service.chestPrecheck(VIEWER, "increment", MenuInteraction.PRIMARY));
    }

    private void hello(int version) throws MenuProtocolException {
        service.receive(VIEWER, MenuCodec.encodeClient(new MenuClientMessage.Hello(version)));
    }
}
