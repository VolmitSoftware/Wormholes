package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerInfo;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientMenusTest {
    @Test
    public void loaderHooksDriveTheSharedSession() throws MenuProtocolException {
        List<byte[]> sent = new ArrayList<>();
        AtomicBoolean channel = new AtomicBoolean();
        ClientMenus.initialize(sent::add, channel::get);

        ClientMenus.connected();
        ClientMenus.tick();
        assertTrue(sent.isEmpty());
        channel.set(true);
        ClientMenus.tick();
        ClientMenus.receive(MenuCodec.encodeServer(new MenuServerMessage.Accept(1, new MenuServerInfo("test-version", "Test 1.0", true))));
        ClientMenus.receive(MenuCodec.encodeServer(new MenuServerMessage.Open(MenuScreen.builder("portal.home").title("Portal").revision(2L).build())));
        assertTrue(ClientMenus.session().back());

        assertEquals(new MenuClientMessage.Hello(1), MenuCodec.decodeClient(sent.get(0)));
        assertEquals(new MenuClientMessage.Back(2L), MenuCodec.decodeClient(sent.get(1)));
        assertEquals(1, ClientMenus.session().version());

        ClientMenus.disconnected();
        assertEquals(0, ClientMenus.session().version());
    }
}
