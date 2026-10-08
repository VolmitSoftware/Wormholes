package art.arcane.wormholes.modded.client;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WormholesClientConfigRollKeyTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void cameraRollEaseDefaultsToAThirdOfASecond() throws Exception {
        Path configDirectory = directory.newFolder().toPath();
        WormholesClientConfig defaults = WormholesClientConfig.load(configDirectory);
        assertEquals(0.35D, defaults.cameraRollEaseSeconds, 0.0D);
        String written = Files.readString(configDirectory.resolve(WormholesClientConfig.FILE_NAME));
        assertTrue(written, written.contains("camera-roll-ease-seconds = 0.35"));
    }

    @Test
    public void cameraRollEaseIsClampedToItsRange() {
        WormholesClientConfig low = new WormholesClientConfig();
        low.cameraRollEaseSeconds = -1.0D;
        low.normalize();
        assertEquals(0.0D, low.cameraRollEaseSeconds, 0.0D);
        WormholesClientConfig high = new WormholesClientConfig();
        high.cameraRollEaseSeconds = 9.0D;
        high.normalize();
        assertEquals(WormholesClientConfig.MAX_CAMERA_ROLL_EASE_SECONDS, high.cameraRollEaseSeconds, 0.0D);
        WormholesClientConfig invalid = new WormholesClientConfig();
        invalid.cameraRollEaseSeconds = Double.NaN;
        invalid.normalize();
        assertEquals(0.0D, invalid.cameraRollEaseSeconds, 0.0D);
    }
}
