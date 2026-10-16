package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.transit.TransitEdit;
import art.arcane.wormholes.menu.transit.TransitMenuView;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.ScaleRuleSettings;
import art.arcane.wormholes.transit.TransitionProfile;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftTransitMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("7a0517e0-0000-4000-8000-0000000000aa");

    private MinecraftPortalRegistry portals;
    private MenuService menus;
    private ServerPlayer player;
    private MinecraftPortal portal;
    private MenuViewer viewer;
    private MinecraftTransitMenuHost host;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        portals = mock(MinecraftPortalRegistry.class);
        menus = mock(MenuService.class);
        player = mock(ServerPlayer.class);
        portal = portal();
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.menus()).thenReturn(menus);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(settings.getTransit()).thenReturn(new TransitConfig());
        when(portals.get(PORTAL)).thenReturn(portal);
        when(portals.update(eq(player), eq(PORTAL), any())).thenAnswer(invocation -> {
            Consumer<MinecraftPortal> mutation = invocation.getArgument(2);
            mutation.accept(portal);
            return true;
        });
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftTransitMenuHost(runtime);
    }

    @Test
    public void viewReadsThePortalTransitSettingsWithGravity() {
        portal.setMembrane(true);
        portal.setGravityRotationEnabled(true);

        TransitMenuView view = host.transit(PORTAL).orElseThrow();

        assertNull(view.momentum());
        assertEquals(MomentumPolicy.of(MomentumPolicy.Mode.PRESERVE), view.effectiveMomentum());
        assertNull(view.orientation());
        assertEquals(OrientationPolicy.FRAME, view.effectiveOrientation());
        assertTrue(view.membrane());
        assertFalse(view.bounce());
        assertTrue(view.profile().isNone());
        assertEquals(ScaleRuleSettings.DEFAULT, view.scaleRule());
        assertTrue(view.gravityRotation());
        assertTrue(host.supportsGravityRotation());
        assertTrue(host.transit(UUID.randomUUID()).isEmpty());
    }

    @Test
    public void editsGoThroughTheRegistryAndRefreshThePortalMenus() {
        MomentumPolicy momentum = MomentumPolicy.of(MomentumPolicy.Mode.values()[1]).withFactor(2.5D);
        TransitionProfile profile = TransitionProfile.NONE.withThresholdEffect("minecraft:end_rod");
        ScaleRule scale = ScaleRuleSettings.withMax(ScaleRuleSettings.DEFAULT, 2.0D);

        host.apply(viewer, PORTAL, new TransitEdit.Gravity(true));
        host.apply(viewer, PORTAL, new TransitEdit.Momentum(momentum));
        host.apply(viewer, PORTAL, new TransitEdit.Orientation(OrientationPolicy.values()[1]));
        host.apply(viewer, PORTAL, new TransitEdit.Membrane(true));
        host.apply(viewer, PORTAL, new TransitEdit.Bounce(true));
        host.apply(viewer, PORTAL, new TransitEdit.Profile(profile));
        host.apply(viewer, PORTAL, new TransitEdit.Scale(scale));

        assertTrue(portal.isGravityRotationEnabled());
        assertEquals(momentum, portal.getMomentum());
        assertEquals(OrientationPolicy.values()[1], portal.getOrientation());
        assertTrue(portal.isMembrane());
        assertTrue(portal.isBounce());
        assertEquals(profile, portal.getTransitionProfile());
        assertEquals(scale, portal.getScaleRule());
        verify(menus, times(7)).invalidate(new MenuScope.Portal(PORTAL));
    }

    @Test
    public void refusedUpdatesRefreshNothing() {
        when(portals.update(eq(player), eq(PORTAL), any())).thenReturn(false);

        host.apply(viewer, PORTAL, new TransitEdit.Bounce(true));

        assertFalse(portal.isBounce());
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
