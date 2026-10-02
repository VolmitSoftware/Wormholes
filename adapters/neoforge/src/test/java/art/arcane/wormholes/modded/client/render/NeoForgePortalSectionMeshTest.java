package art.arcane.wormholes.modded.client.render;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.config.NeoForgeClientConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NeoForgePortalSectionMeshTest extends PortalSectionMeshTest {
    @ClassRule
    public static final TemporaryFolder CONFIG_DIRECTORY = new TemporaryFolder();

    private static final ConfigTracker CONFIGS = new ConfigTracker();

    @BeforeClass
    public static void loadClientDefaults() {
        ModContainer container = mock(ModContainer.class);
        when(container.getModId()).thenReturn("wormholes");
        CONFIGS.registerConfig(ModConfig.Type.CLIENT, NeoForgeClientConfig.SPEC, container);
        CONFIGS.loadConfigs(ModConfig.Type.CLIENT, CONFIG_DIRECTORY.getRoot().toPath());
    }

    @AfterClass
    public static void clearClientDefaults() {
        CONFIGS.unloadConfigs(ModConfig.Type.CLIENT);
    }
}
