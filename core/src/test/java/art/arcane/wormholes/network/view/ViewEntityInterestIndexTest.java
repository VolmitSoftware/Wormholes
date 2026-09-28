package art.arcane.wormholes.network.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class ViewEntityInterestIndexTest {
    @Test
    void oneThousandSessionsRouteOnlyTheInterestedSession() {
        ViewEntityInterestIndex<UUID> index = new ViewEntityInterestIndex<UUID>();
        UUID selectedEntity = new UUID(0L, 735L);
        UUID selectedSession = null;
        for (int sessionIndex = 0; sessionIndex < 1_000; sessionIndex++) {
            UUID session = new UUID(1L, sessionIndex + 1L);
            index.activate(session);
            index.replace(session, Set.of(new UUID(0L, sessionIndex + 1L)));
            if (sessionIndex == 734) {
                selectedSession = session;
            }
        }

        assertEquals(List.of(selectedSession), index.sessions(selectedEntity));
        assertEquals(1_000, index.sessionCount());
        assertEquals(1_000, index.entityCount());
    }

    @Test
    void replacementRetirementAndCloseRemoveStaleRoutes() {
        ViewEntityInterestIndex<String> index = new ViewEntityInterestIndex<String>();
        UUID formerEntity = new UUID(0L, 1L);
        UUID currentEntity = new UUID(0L, 2L);
        index.activate("session");
        assertTrue(index.replace("session", Set.of(formerEntity)));
        assertTrue(index.replace("session", Set.of(currentEntity)));
        assertTrue(index.sessions(formerEntity).isEmpty());
        assertEquals(List.of("session"), index.sessions(currentEntity));

        index.retire("session");
        assertTrue(index.sessions(currentEntity).isEmpty());
        assertFalse(index.replace("session", Set.of(formerEntity)));

        index.activate("replacement");
        index.replace("replacement", Set.of(formerEntity));
        index.close();
        index.activate("late");
        assertTrue(index.sessions(formerEntity).isEmpty());
        assertFalse(index.replace("late", Set.of(formerEntity)));
        assertEquals(0, index.sessionCount());
        assertEquals(0, index.entityCount());
    }
}
