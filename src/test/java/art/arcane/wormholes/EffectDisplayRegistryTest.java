package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

final class EffectDisplayRegistryTest {
    private static final String EFFECT_ENTITY_TAG = "wormholes_fx";

    @Test
    void taggedDisplayIsRecognisedAsEffectEntity() {
        assertTrue(EffectDisplayRegistry.isEffectEntity(entity(Display.class, Set.of(EFFECT_ENTITY_TAG))));
        assertTrue(EffectDisplayRegistry.isEffectEntity(entity(Display.class, Set.of("other", EFFECT_ENTITY_TAG))));
    }

    @Test
    void untaggedDisplayIsNotAnEffectEntity() {
        assertFalse(EffectDisplayRegistry.isEffectEntity(entity(Display.class, Set.of())));
        assertFalse(EffectDisplayRegistry.isEffectEntity(entity(Display.class, Set.of("wormholes_fx_other"))));
    }

    @Test
    void taggedNonDisplayEntityIsNotAnEffectEntity() {
        assertFalse(EffectDisplayRegistry.isEffectEntity(entity(Entity.class, Set.of(EFFECT_ENTITY_TAG))));
    }

    @Test
    void serverStopDoesNotTouchEntitiesAfterRegionsHaveStopped() {
        EffectDisplayRegistry registry = new EffectDisplayRegistry();
        Display display = mock(Display.class);
        UUID id = UUID.randomUUID();
        when(display.getUniqueId()).thenReturn(id);
        registry.track(display);
        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            scheduler.when(() -> FoliaScheduler.isStopping(server)).thenReturn(true);
            registry.drain(registry.beginShutdown());
            bukkit.verify(Bukkit::getServer);
            bukkit.verifyNoMoreInteractions();
            scheduler.verify(() -> FoliaScheduler.isStopping(server));
            scheduler.verifyNoMoreInteractions();
        }
        verify(display).getUniqueId();
        verify(display).addScoreboardTag(EFFECT_ENTITY_TAG);
        verifyNoMoreInteractions(display);
        assertTrue(registry.isClosing());
    }

    @Test
    void pluginReloadRemovesDisplaysOnTheirOwningRegion() {
        EffectDisplayRegistry registry = new EffectDisplayRegistry();
        Display display = mock(Display.class);
        UUID id = UUID.randomUUID();
        when(display.getUniqueId()).thenReturn(id);
        when(display.isValid()).thenReturn(true);
        registry.track(display);
        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(display);
            scheduler.when(() -> FoliaScheduler.isStopping(server)).thenReturn(false);
            scheduler.when(() -> FoliaScheduler.isFoliaThreading(server)).thenReturn(true);
            scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(display)).thenReturn(true);
            registry.drain(registry.beginShutdown());
            verify(display).remove();
        }
        assertTrue(registry.isClosing());
    }

    private static Entity entity(Class<? extends Entity> type, Set<String> scoreboardTags) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getScoreboardTags" -> scoreboardTags;
            case "equals" -> Boolean.valueOf(proxy == args[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> type.getSimpleName() + scoreboardTags;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return type.cast(Proxy.newProxyInstance(EffectDisplayRegistryTest.class.getClassLoader(),
            new Class<?>[] { type }, handler));
    }
}
