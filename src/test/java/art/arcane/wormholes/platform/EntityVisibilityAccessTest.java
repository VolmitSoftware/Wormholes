package art.arcane.wormholes.platform;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

final class EntityVisibilityAccessTest {
    @Test
    void appliesVisibilityIndependentlyForEachViewer() {
        NativePlayer first = player(NativePlayer.class);
        NativePlayer second = player(NativePlayer.class);
        UUID entityId = UUID.randomUUID();
        Plugin external = mock(Plugin.class);
        first.invertedVisibilityEntities.put(entityId, references(external));

        assertFalse(EntityVisibilityAccess.isVisible(first, entityId, true, null));
        assertTrue(EntityVisibilityAccess.isVisible(second, entityId, true, null));
        assertTrue(EntityVisibilityAccess.isVisible(first, entityId, false, null));
        assertFalse(EntityVisibilityAccess.isVisible(second, entityId, false, null));
    }

    @Test
    void ignoresOnlyTheExcludedPluginHidesAndPreservesShowGrants() {
        NativePlayer observer = player(NativePlayer.class);
        UUID entityId = UUID.randomUUID();
        Plugin excluded = mock(Plugin.class);
        Plugin external = mock(Plugin.class);
        Set<WeakReference<Plugin>> plugins = references(excluded);
        observer.invertedVisibilityEntities.put(entityId, plugins);

        assertTrue(EntityVisibilityAccess.isVisible(observer, entityId, true, excluded));
        assertTrue(EntityVisibilityAccess.isVisible(observer, entityId, false, excluded));

        WeakReference<Plugin> externalReference = new WeakReference<Plugin>(external);
        plugins.add(externalReference);
        assertFalse(EntityVisibilityAccess.isVisible(observer, entityId, true, excluded));
        assertTrue(EntityVisibilityAccess.isVisible(observer, entityId, false, excluded));

        plugins.remove(externalReference);
        assertTrue(EntityVisibilityAccess.isVisible(observer, entityId, true, excluded));
    }

    @Test
    void preservesLegacyAndClearedPluginReferences() {
        NativePlayer observer = player(NativePlayer.class);
        UUID entityId = UUID.randomUUID();
        Plugin excluded = mock(Plugin.class);
        Set<WeakReference<Plugin>> plugins = references(excluded);
        plugins.add(null);
        observer.invertedVisibilityEntities.put(entityId, plugins);

        assertFalse(EntityVisibilityAccess.isVisible(observer, entityId, true, excluded));
        assertTrue(EntityVisibilityAccess.isVisible(observer, entityId, false, excluded));

        plugins.remove(null);
        WeakReference<Plugin> cleared = new WeakReference<Plugin>(mock(Plugin.class));
        cleared.clear();
        plugins.add(cleared);
        assertFalse(EntityVisibilityAccess.isVisible(observer, entityId, true, excluded));
        assertTrue(EntityVisibilityAccess.isVisible(observer, entityId, false, excluded));
        assertFalse(EntityVisibilityAccess.isVisible(observer, entityId, true, null));
    }

    @Test
    void preservesEmptyMapEntriesAsInversions() {
        NativePlayer observer = player(NativePlayer.class);
        UUID entityId = UUID.randomUUID();
        observer.invertedVisibilityEntities.put(entityId, new HashSet<WeakReference<Plugin>>());

        assertFalse(EntityVisibilityAccess.isVisible(observer, entityId, true, mock(Plugin.class)));
        assertTrue(EntityVisibilityAccess.isVisible(observer, entityId, false, mock(Plugin.class)));
    }

    @Test
    void findsTheVisibilityMapInInheritedImplementations() {
        NativePlayer observer = player(DerivedPlayer.class);
        UUID entityId = UUID.randomUUID();
        observer.invertedVisibilityEntities.put(entityId, references(mock(Plugin.class)));

        assertFalse(EntityVisibilityAccess.isVisible(observer, entityId, true, null));
    }

    @Test
    void alwaysIncludesTheObserverAndRejectsMissingInputs() {
        Player observer = mock(Player.class);
        UUID observerId = UUID.randomUUID();
        when(observer.getUniqueId()).thenReturn(observerId);

        assertTrue(EntityVisibilityAccess.isVisible(observer, observerId, false, null));
        assertFalse(EntityVisibilityAccess.isVisible(null, observerId, true, null));
        assertFalse(EntityVisibilityAccess.isVisible(observer, null, true, null));
    }

    @Test
    void withholdsEntitiesAndReportsMissingCapabilityOnlyOnce() {
        MissingVisibilityPlayer observer = player(MissingVisibilityPlayer.class);
        UUID entityId = UUID.randomUUID();
        RecordingHandler handler = new RecordingHandler();
        Logger logger = Logger.getLogger("Wormholes");
        logger.addHandler(handler);
        try {
            assertFalse(EntityVisibilityAccess.isVisible(observer, entityId, true, null));
            assertFalse(EntityVisibilityAccess.isVisible(observer, entityId, false, null));

            assertEquals(1, handler.records.size());
            assertEquals(Level.WARNING, handler.records.getFirst().getLevel());
            assertNotNull(handler.records.getFirst().getThrown());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void rejectsAnIncompatibleVisibilityField() {
        InvalidVisibilityPlayer observer = player(InvalidVisibilityPlayer.class);

        assertFalse(EntityVisibilityAccess.isVisible(observer, UUID.randomUUID(), true, null));
    }

    private static <T extends Player> T player(Class<T> type) {
        T observer = mock(type, withSettings().useConstructor());
        when(observer.getUniqueId()).thenReturn(UUID.randomUUID());
        return observer;
    }

    private static Set<WeakReference<Plugin>> references(Plugin plugin) {
        Set<WeakReference<Plugin>> references = new HashSet<WeakReference<Plugin>>();
        references.add(new WeakReference<Plugin>(plugin));
        return references;
    }

    abstract static class NativePlayer implements Player {
        private final Map<UUID, Set<WeakReference<Plugin>>> invertedVisibilityEntities =
            new HashMap<UUID, Set<WeakReference<Plugin>>>();
    }

    abstract static class DerivedPlayer extends NativePlayer {
    }

    abstract static class MissingVisibilityPlayer implements Player {
    }

    abstract static class InvalidVisibilityPlayer implements Player {
        private final String invertedVisibilityEntities = "unsupported";
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<LogRecord>();

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
