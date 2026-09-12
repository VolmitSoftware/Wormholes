package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderSnapshot;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.PortalType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WormholesNamedPortalSnapshotTest {
    @Test
    void namedAndUuidLookupsWorkWithoutAPlayerAndKeepSignedBlockCoordinates() {
        ILocalPortal portal = linkedPortal("Spawn Gate", new Vector(-12.25D, -3.1D, 44.9D));
        PlaceholderSnapshot<WormholesNamedPortalSnapshot> snapshot = new PlaceholderSnapshot<>();
        snapshot.publish(WormholesNamedPortalSnapshot.capture(List.of(portal), null));
        WormholesPlaceholderExpansion expansion = expansion(snapshot);

        assertEquals("-13", expansion.onRequest(null, "PORTAL.SPAWN-GATE.DESTINATION.X"));
        assertEquals("-4", expansion.onRequest(null, "portal.spawn-gate.destination.y"));
        assertEquals("44", expansion.onRequest(null, "portal." + portal.getId() + ".destination.z"));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(null, "portal.spawn-gate.destination.time-remaining"));
    }

    @Test
    void duplicateNormalizedNamesAreUnavailableWhileEveryUuidRemainsUsable() {
        ILocalPortal first = linkedPortal("Spawn Gate", new Vector(10, 70, 30));
        ILocalPortal second = linkedPortal("SPAWN_GATE", new Vector(20, 70, 30));
        ILocalPortal third = linkedPortal("Spawn---Gate", new Vector(30, 70, 30));
        WormholesNamedPortalSnapshot snapshot = WormholesNamedPortalSnapshot.capture(List.of(first, second, third), null);

        assertEquals(PlaceholderValues.UNAVAILABLE, resolve(snapshot, "spawn-gate", "x"));
        assertEquals("10", resolve(snapshot, first.getId().toString(), "x"));
        assertEquals("20", resolve(snapshot, second.getId().toString(), "x"));
        assertEquals("30", resolve(snapshot, third.getId().toString(), "x"));
    }

    @Test
    void republishingReflectsRenamesAndRemovesDeletedPortals() {
        ILocalPortal portal = linkedPortal("Old Gate", new Vector(10, 70, 30));
        PlaceholderSnapshot<WormholesNamedPortalSnapshot> snapshot = new PlaceholderSnapshot<>();
        WormholesPlaceholderExpansion expansion = expansion(snapshot);
        snapshot.publish(WormholesNamedPortalSnapshot.capture(List.of(portal), null));
        assertEquals("10", expansion.onRequest(null, "portal.old-gate.destination.x"));

        when(portal.getName()).thenReturn("New Gate");
        snapshot.publish(WormholesNamedPortalSnapshot.capture(List.of(portal), null));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(null, "portal.old-gate.destination.x"));
        assertEquals("10", expansion.onRequest(null, "portal.new-gate.destination.x"));

        snapshot.publish(WormholesNamedPortalSnapshot.capture(List.of(), null));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(null, "portal.new-gate.destination.x"));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(null, "portal." + portal.getId() + ".destination.x"));
    }

    @Test
    void missingPortalsAreUnavailableAndUnknownFieldsRemainUnresolved() {
        PlaceholderSnapshot<WormholesNamedPortalSnapshot> snapshot = new PlaceholderSnapshot<>();
        WormholesPlaceholderExpansion expansion = expansion(snapshot);

        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(null, "portal.missing.destination.x"));
        assertEquals(PlaceholderValues.UNAVAILABLE, expansion.onRequest(null, "portal.missing.destination.time-remaining"));
        assertNull(expansion.onRequest(null, "portal.missing.destination.q"));
        assertNull(expansion.onRequest(null, "portal.missing"));
        assertNull(expansion.onRequest(null, "portal..destination.x"));
        assertNull(expansion.onRequest(null, "portal.*"));
    }

    @Test
    void selectorsStripFormattingAndPreserveUnicodeLettersAndDigits() {
        assertEquals("spawn-gate", WormholesNamedPortalSnapshot.selector(" &aSpawn__Gate! "));
        assertEquals("spawn-gate", WormholesNamedPortalSnapshot.selector("[ff0000]Spawn Gate"));
        assertEquals("spawn-gate", WormholesNamedPortalSnapshot.selector("<red>Spawn</red> §bGate"));
        assertEquals("\u6771\u4eac-2", WormholesNamedPortalSnapshot.selector("\u6771\u4eac 2"));
        assertEquals("", WormholesNamedPortalSnapshot.selector("---"));
        assertEquals("", WormholesNamedPortalSnapshot.selector(null));
    }

    @Test
    void resolvingOnAnotherThreadOnlyReadsCapturedValues() {
        ILocalPortal portal = linkedPortal("Async Gate", new Vector(-0.1D, 70, 30));
        ITunnel tunnel = portal.getTunnel();
        IPortal destination = tunnel.getDestination();
        PlaceholderSnapshot<WormholesNamedPortalSnapshot> snapshot = new PlaceholderSnapshot<>();
        snapshot.publish(WormholesNamedPortalSnapshot.capture(List.of(portal), null));
        WormholesPlaceholderExpansion expansion = expansion(snapshot);
        clearInvocations(portal, tunnel, destination);

        assertEquals("-1", CompletableFuture.supplyAsync(
            () -> expansion.onRequest(null, "portal.async-gate.destination.x")).join());
        verifyNoInteractions(portal, tunnel, destination);
    }

    private static String resolve(WormholesNamedPortalSnapshot snapshot, String selector, String field) {
        return WormholesNamedPortalSnapshot.resolve(snapshot, null, selector + ".destination." + field, 1_000L);
    }

    private static WormholesPlaceholderExpansion expansion(PlaceholderSnapshot<WormholesNamedPortalSnapshot> snapshot) {
        return new WormholesPlaceholderExpansion("2.0.5-26.2", WormholesPlaceholders.registry(
            new PlaceholderSnapshot<>(), new PlayerSnapshotStore<>(), snapshot), Logger.getAnonymousLogger());
    }

    private static ILocalPortal linkedPortal(String name, Vector coordinates) {
        ILocalPortal portal = mock(ILocalPortal.class);
        ITunnel tunnel = mock(ITunnel.class);
        IPortal destination = mock(IPortal.class);
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getName()).thenReturn(name);
        when(portal.getType()).thenReturn(PortalType.PORTAL);
        when(portal.hasTunnel()).thenReturn(true);
        when(portal.getTunnel()).thenReturn(tunnel);
        when(tunnel.getDestination()).thenReturn(destination);
        when(destination.getOrigin()).thenReturn(coordinates);
        return portal;
    }
}
