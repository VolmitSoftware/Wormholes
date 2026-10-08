package art.arcane.wormholes.modded.client;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WormholesClientConfigShapeKeysTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void shapeKeysDefaultToEightSubdivisionsAndNoFeather() throws Exception {
        Path configDirectory = directory.newFolder().toPath();
        WormholesClientConfig defaults = WormholesClientConfig.load(configDirectory);
        assertEquals(8, defaults.portalShapeSubdivisions);
        assertEquals(0.0D, defaults.portalEdgeFeather, 0.0D);
        String written = Files.readString(configDirectory.resolve(WormholesClientConfig.FILE_NAME));
        assertTrue(written, written.contains("portal-shape-subdivisions = 8"));
        assertTrue(written, written.contains("portal-edge-feather = 0.0"));
    }

    @Test
    public void shapeKeysAreClampedToTheirRanges() {
        WormholesClientConfig low = new WormholesClientConfig();
        low.portalShapeSubdivisions = 0;
        low.portalEdgeFeather = -1.0D;
        low.normalize();
        assertEquals(1, low.portalShapeSubdivisions);
        assertEquals(0.0D, low.portalEdgeFeather, 0.0D);
        WormholesClientConfig high = new WormholesClientConfig();
        high.portalShapeSubdivisions = 99;
        high.portalEdgeFeather = 5.0D;
        high.normalize();
        assertEquals(WormholesClientConfig.MAX_PORTAL_SHAPE_SUBDIVISIONS, high.portalShapeSubdivisions);
        assertEquals(WormholesClientConfig.MAX_PORTAL_EDGE_FEATHER, high.portalEdgeFeather, 0.0D);
        WormholesClientConfig invalid = new WormholesClientConfig();
        invalid.portalEdgeFeather = Double.NaN;
        invalid.normalize();
        assertEquals(0.0D, invalid.portalEdgeFeather, 0.0D);
    }

    @Test
    public void writtenShapeKeysAreReadBack() throws Exception {
        Path configDirectory = directory.newFolder().toPath();
        Files.writeString(configDirectory.resolve(WormholesClientConfig.FILE_NAME),
            "portal-shape-subdivisions = 4\nportal-edge-feather = 0.25\n");
        WormholesClientConfig loaded = WormholesClientConfig.load(configDirectory);
        assertEquals(4, loaded.portalShapeSubdivisions);
        assertEquals(0.25D, loaded.portalEdgeFeather, 0.0D);
    }
}
