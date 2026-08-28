package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class EntityRenderLocalOccluderEnvelopeTest {
    @Test
    public void translatedLabelEnvelopeFullyBehindApertureIsClaimed() {
        SettingsSnapshot settings = applyExactFrustumSettings();
        try {
            Frustum4D frustum = frustum();
            assertTrue(EntityRenderLocalOccluder.envelopeFullyProjected(
                1.0D, 0.75D, 6.5D,
                2.0D, 2.5D, 7.5D,
                new Vector(1.5D, 1.5D, 5.0D), PortalFrame.canonical(Direction.N), frustum,
                true, 0.01D, 16.0D));
        } finally {
            settings.restore();
        }
    }

    @Test
    public void partiallyExposedEnvelopeIsNotClaimed() {
        SettingsSnapshot settings = applyExactFrustumSettings();
        try {
            Frustum4D frustum = frustum();
            assertFalse(EntityRenderLocalOccluder.envelopeFullyProjected(
                2.5D, 0.75D, 6.5D,
                4.0D, 2.5D, 7.5D,
                new Vector(1.5D, 1.5D, 5.0D), PortalFrame.canonical(Direction.N), frustum,
                true, 0.01D, 16.0D));
        } finally {
            settings.restore();
        }
    }

    @Test
    public void envelopeCrossingPortalPlaneOrDepthLimitIsNotClaimed() {
        SettingsSnapshot settings = applyExactFrustumSettings();
        try {
            Frustum4D frustum = frustum();
            Vector origin = new Vector(1.5D, 1.5D, 5.0D);
            PortalFrame frame = PortalFrame.canonical(Direction.N);
            assertFalse(EntityRenderLocalOccluder.envelopeFullyProjected(
                1.0D, 0.75D, 4.9D,
                2.0D, 2.5D, 5.5D,
                origin, frame, frustum, true, 0.01D, 16.0D));
            assertFalse(EntityRenderLocalOccluder.envelopeFullyProjected(
                1.0D, 0.75D, 20.5D,
                2.0D, 2.5D, 21.5D,
                origin, frame, frustum, true, 0.01D, 16.0D));
        } finally {
            settings.restore();
        }
    }

    private static Frustum4D frustum() {
        return new Frustum4D(
            new Location(null, 1.5D, 1.5D, 0.0D),
            new TestStructure(), 16.0D, 16.0D);
    }

    private static SettingsSnapshot applyExactFrustumSettings() {
        SettingsSnapshot snapshot = new SettingsSnapshot(
            Settings.NEAR_PLANE_PADDING,
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS,
            Settings.FRUSTUM_CULLING_RATIO);
        Settings.NEAR_PLANE_PADDING = 0.0D;
        Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 0.0D;
        Settings.FRUSTUM_CULLING_RATIO = 0.2D;
        return snapshot;
    }

    private record SettingsSnapshot(double nearPlanePadding,
                                    double aperturePadding,
                                    double cullingRatio) {
        private void restore() {
            Settings.NEAR_PLANE_PADDING = nearPlanePadding;
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = aperturePadding;
            Settings.FRUSTUM_CULLING_RATIO = cullingRatio;
        }
    }

    private static final class TestStructure extends PortalStructure {
        @Override
        public AxisAlignedBB getArea() {
            return new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D);
        }

        @Override
        public Location getCenter() {
            return new Location(null, 1.5D, 1.5D, 5.0D);
        }

        @Override
        public List<AxisAlignedBB> getCachedApertureFaces(Direction face) {
            if (face != Direction.N && face != Direction.S) {
                return List.of();
            }
            KList<AxisAlignedBB> faces = new KList<AxisAlignedBB>();
            faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
            return faces;
        }
    }
}
