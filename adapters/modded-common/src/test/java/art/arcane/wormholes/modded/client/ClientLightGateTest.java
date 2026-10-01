package art.arcane.wormholes.modded.client;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientLightGateTest {
    @After
    public void release() {
        while (ClientLightGate.depth() > 0) {
            ClientLightGate.exit();
        }
    }

    @Test
    public void onlyTheEnteredLevelAndEngineAreSuppressed() {
        Object level = new Object();
        Object engine = new Object();
        assertFalse(ClientLightGate.suppresses(engine));
        assertFalse(ClientLightGate.suppressesLevel(level));
        ClientLightGate.enter(level, engine);
        assertTrue(ClientLightGate.suppresses(engine));
        assertTrue(ClientLightGate.suppressesLevel(level));
        assertFalse(ClientLightGate.suppresses(new Object()));
        assertFalse(ClientLightGate.suppressesLevel(new Object()));
        assertFalse(ClientLightGate.suppresses(null));
        assertFalse(ClientLightGate.suppressesLevel(null));
        ClientLightGate.exit();
        assertFalse(ClientLightGate.suppresses(engine));
        assertFalse(ClientLightGate.suppressesLevel(level));
    }

    @Test
    public void nestedEntriesReleaseOnTheOutermostExit() {
        Object level = new Object();
        Object engine = new Object();
        ClientLightGate.enter(level, engine);
        ClientLightGate.enter(level, engine);
        ClientLightGate.exit();
        assertTrue(ClientLightGate.suppresses(engine));
        ClientLightGate.exit();
        assertFalse(ClientLightGate.suppresses(engine));
        ClientLightGate.exit();
        assertFalse(ClientLightGate.suppresses(engine));
    }
}
