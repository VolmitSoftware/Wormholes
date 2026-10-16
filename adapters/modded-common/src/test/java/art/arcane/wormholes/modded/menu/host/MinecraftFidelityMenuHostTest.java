package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.volume.LodProfile;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.fidelity.FidelityEdit;
import art.arcane.wormholes.menu.fidelity.FidelityMenuView;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.render.FidelitySettings;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftFidelityMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("f1de0000-0000-4000-8000-0000000000aa");

    private MinecraftPortalRegistry portals;
    private MenuService menus;
    private ServerPlayer player;
    private MinecraftPortal portal;
    private MenuViewer viewer;
    private MinecraftFidelityMenuHost host;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        portals = mock(MinecraftPortalRegistry.class);
        menus = mock(MenuService.class);
        player = mock(ServerPlayer.class);
        portal = portal();
        when(runtime.portals()).thenReturn(portals);
        when(runtime.menus()).thenReturn(menus);
        when(portals.get(PORTAL)).thenReturn(portal);
        when(portals.update(eq(player), eq(PORTAL), any())).thenAnswer(invocation -> {
            Consumer<MinecraftPortal> mutation = invocation.getArgument(2);
            mutation.accept(portal);
            return true;
        });
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftFidelityMenuHost(runtime);
    }

    @Test
    public void viewFallsBackToTheServerDefaults() {
        portal.setLodProfile(LodProfile.values()[0]);

        FidelityMenuView view = host.fidelity(PORTAL).orElseThrow();

        assertNull(view.atmosphere());
        assertNull(view.acoustics());
        assertEquals(LodProfile.values()[0], view.lod());
        assertNull(view.blockEntities());
        assertEquals(FidelitySettings.atmosphereModeDefault, view.effectiveAtmosphere());
        assertEquals(FidelitySettings.acousticsProfileDefault, view.effectiveAcoustics());
        assertEquals(LodProfile.values()[0], view.effectiveLod());
        assertEquals(FidelitySettings.blockEntities, view.effectiveBlockEntities());
        assertTrue(host.fidelity(UUID.randomUUID()).isEmpty());
    }

    @Test
    public void editsGoThroughTheRegistryAndRefreshThePortalMenus() {
        host.apply(viewer, PORTAL, new FidelityEdit.Atmosphere(AtmosphereMode.values()[0]));
        host.apply(viewer, PORTAL, new FidelityEdit.Acoustics(AcousticsProfile.values()[0]));
        host.apply(viewer, PORTAL, new FidelityEdit.Lod(LodProfile.values()[0]));
        host.apply(viewer, PORTAL, new FidelityEdit.BlockEntities(Boolean.FALSE));

        assertEquals(AtmosphereMode.values()[0], portal.getAtmosphereMode());
        assertEquals(AcousticsProfile.values()[0], portal.getAcousticsProfile());
        assertEquals(LodProfile.values()[0], portal.getLodProfile());
        assertEquals(Boolean.FALSE, portal.getBlockEntities());
        verify(menus, times(4)).invalidate(new MenuScope.Portal(PORTAL));

        host.apply(viewer, PORTAL, new FidelityEdit.BlockEntities(null));
        assertNull(portal.getBlockEntities());
    }

    @Test
    public void refusedUpdatesRefreshNothing() {
        when(portals.update(eq(player), eq(PORTAL), any())).thenReturn(false);

        host.apply(viewer, PORTAL, new FidelityEdit.Lod(LodProfile.values()[0]));

        assertNull(portal.getLodProfile());
        verify(menus, never()).invalidate(any());
    }

    private static MinecraftPortal portal() {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3d(0, 64, 0), new Vec3d(0, 65, 0)));
        Map<String, Object> values = new HashMap<>();
        values.put("owner", UUID.randomUUID().toString());
        values.put("type", "PORTAL");
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(PORTAL, geometry.getApertureCenter(), "Alpha",
            Frame.canonical(Face.N), true), geometry, "minecraft:overworld", values));
    }
}
