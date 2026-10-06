package art.arcane.wormholes.render;

import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.optics.math.Vec3d;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.Settings;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.volume.LocalEntityEnvelope;
import art.arcane.optics.volume.ViewVolume;

public final class EntityRenderLocalOccluderEnvelopeTest {
    @Test
    public void translatedLabelEnvelopeFullyBehindApertureIsClaimed() {
        SettingsSnapshot settings = applyExactFrustumSettings();
        try {
            ViewVolume frustum = frustum();
            assertTrue(LocalEntityEnvelope.envelopeFullyProjected(
                1.0D, 0.75D, 6.5D,
                2.0D, 2.5D, 7.5D,
                new Vec3d(1.5D, 1.5D, 5.0D), Frame.canonical(Face.N), frustum,
                true, 0.01D, 16.0D));
        } finally {
            settings.restore();
        }
    }

    @Test
    public void partiallyExposedEnvelopeIsNotClaimed() {
        SettingsSnapshot settings = applyExactFrustumSettings();
        try {
            ViewVolume frustum = frustum();
            assertFalse(LocalEntityEnvelope.envelopeFullyProjected(
                2.5D, 0.75D, 6.5D,
                4.0D, 2.5D, 7.5D,
                new Vec3d(1.5D, 1.5D, 5.0D), Frame.canonical(Face.N), frustum,
                true, 0.01D, 16.0D));
        } finally {
            settings.restore();
        }
    }

    @Test
    public void envelopeCrossingPortalPlaneOrDepthLimitIsNotClaimed() {
        SettingsSnapshot settings = applyExactFrustumSettings();
        try {
            ViewVolume frustum = frustum();
            Vector origin = new Vector(1.5D, 1.5D, 5.0D);
            Frame frame = Frame.canonical(Face.N);
            assertFalse(LocalEntityEnvelope.envelopeFullyProjected(
                1.0D, 0.75D, 4.9D,
                2.0D, 2.5D, 5.5D,
                BukkitGeometry.vector(origin), frame, frustum, true, 0.01D, 16.0D));
            assertFalse(LocalEntityEnvelope.envelopeFullyProjected(
                1.0D, 0.75D, 20.5D,
                2.0D, 2.5D, 21.5D,
                BukkitGeometry.vector(origin), frame, frustum, true, 0.01D, 16.0D));
        } finally {
            settings.restore();
        }
    }

    private static ViewVolume frustum() {
        return new ViewVolume(BukkitGeometry.vector(new Location(null, 1.5D, 1.5D, 0.0D)), new TestStructure(), new ViewVolume.Options(16.0D, 16.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
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
        public Box getArea() {
            return new Box(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D);
        }

        @Override
        public Location getCenter() {
            return new Location(null, 1.5D, 1.5D, 5.0D);
        }

        @Override
        public List<Box> getCachedApertureFaces(Face face) {
            if (face != Face.N && face != Face.S) {
                return List.of();
            }
            KList<Box> faces = new KList<Box>();
            faces.add(new Box(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
            return faces;
        }
    }
}
