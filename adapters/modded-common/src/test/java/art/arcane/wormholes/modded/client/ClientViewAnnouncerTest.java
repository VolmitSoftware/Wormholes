package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewMessage;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientViewAnnouncerTest {
    @Test
    public void announcesOncePerAcceptedSession() {
        ClientViewAnnouncer announcer = new ClientViewAnnouncer();
        ClientViewMessage.Accept accept = accept(1);
        assertTrue(announcer.due(true, true, accept, true));
        assertFalse(announcer.due(true, true, accept, true));
    }

    @Test
    public void waitsForThePlayerBeforeAnnouncing() {
        ClientViewAnnouncer announcer = new ClientViewAnnouncer();
        ClientViewMessage.Accept accept = accept(1);
        assertFalse(announcer.due(true, true, accept, false));
        assertTrue(announcer.due(true, true, accept, true));
    }

    @Test
    public void announcesAgainForANewAccept() {
        ClientViewAnnouncer announcer = new ClientViewAnnouncer();
        assertTrue(announcer.due(true, true, accept(1), true));
        assertTrue(announcer.due(true, true, accept(1), true));
    }

    @Test
    public void staysQuietWhenInactiveOrDisabled() {
        ClientViewAnnouncer announcer = new ClientViewAnnouncer();
        ClientViewMessage.Accept accept = accept(1);
        assertFalse(announcer.due(true, false, accept, true));
        assertFalse(announcer.due(true, true, null, true));
        assertFalse(announcer.due(false, true, accept, true));
    }

    private static ClientViewMessage.Accept accept(int sessionId) {
        return new ClientViewMessage.Accept(sessionId, 1L, 20, 524288, 7L, 8);
    }
}
