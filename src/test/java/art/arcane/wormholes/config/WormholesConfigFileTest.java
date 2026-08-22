package art.arcane.wormholes.config;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.door.DoorRecipeSettings;
import art.arcane.wormholes.door.PocketShell;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesConfigFileTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsEveryConfigKey() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        Path file = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);

        assertEquals(VisualQualityProfile.AUTO, settings.getVisualQualityProfile());
        assertTrue(settings.getMain().dimensionalDoorsEnabled);
        assertEquals(16, settings.getMain().pocketRoomSize);
        assertEquals(1, settings.getProjection().initialResendPasses);
        assertFalse(settings.getNetwork().replication.captureBlockEntityEnabled);
        List<String> emitted = emittedSettings(file);
        assertEquals("schema = 2", emitted.get(0));
        assertEquals("quality = \"auto\"", emitted.get(1));
        assertTrue(emitted.contains("[main]"));
        assertTrue(emitted.contains("[network]"));
        assertTrue(emitted.contains("[network.transport]"));
        assertTrue(emitted.contains("[network.view]"));
        assertTrue(emitted.contains("[network.stats]"));
        assertTrue(emitted.contains("[network.replication]"));
        assertTrue(emitted.contains("[projection]"));
        assertTrue(emitted.contains("[render]"));
        assertTrue(emitted.contains("aperture-padding-blocks = 0.75"));
        assertTrue(emitted.contains("pocket-room-size = 16"));
        assertTrue(emitted.contains("initial-resend-passes = 1"));
        assertTrue(emitted.contains("capture-block-entity-enabled = false"));

        Settings.refresh(settings);
        assertEquals(16, Settings.POCKET_SHELL.size());

        String content = String.join("\n", emitted);
        assertEveryKeyEmitted(content, WormholesConfigFile.class);
    }

    @Test
    void allValuesRoundTripThroughCanonicalWrite() throws IOException {
        File file = tempDir.resolve("wormholes.toml").toFile();
        WormholesConfigFile created = new WormholesConfigFile();
        created.network.enabled = true;
        created.network.listenPort = 9001;
        created.network.trustOnFirstUse = false;
        created.network.transport.compressionLevel = 7;
        created.network.replication.captureBlockEntityEnabled = true;
        created.main.enableParticles = false;
        created.projection.range = 72.0D;
        created.projection.initialResendPasses = 3;
        created.render.entitySpoofing = false;
        TomlCodec.writeCanonical(file, created);

        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(written.contains("enable-particles = false"));
        assertTrue(written.contains("[network.transport]"));
        assertTrue(written.contains("compression-level = 7"));
        assertTrue(written.contains("compression-enabled = true"));
        assertTrue(written.contains("capture-block-entity-enabled = true"));
        assertTrue(written.contains("[projection]"));
        assertTrue(written.contains("initial-resend-passes = 3"));

        TomlCodec.LoadResult<WormholesConfigFile> result = TomlCodec.readExisting(file, WormholesConfigFile.class);
        assertTrue(result.isSuccess());
        assertEquals(9001, result.value().network.listenPort);
        assertFalse(result.value().network.trustOnFirstUse);
        assertEquals(7, result.value().network.transport.compressionLevel);
        assertTrue(result.value().network.replication.captureBlockEntityEnabled);
        assertFalse(result.value().main.enableParticles);
        assertEquals(72.0D, result.value().projection.range);
        assertEquals(3, result.value().projection.initialResendPasses);
        assertFalse(result.value().render.entitySpoofing);
    }

    @Test
    void existingConfigGainsNewlyVisibleKeysOnLoadWithoutLosingValues() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config,
            "schema = 2\n[main]\nlanguage = \"de_DE\"\n[projection]\nrange = 72.0\nblackout-shell-thickness-blocks = 2\n",
            StandardCharsets.UTF_8);

        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        assertEquals("de_DE", settings.getMain().language);
        assertEquals(72.0D, settings.getProjection().range);
        List<String> emitted = emittedSettings(config);
        assertTrue(emitted.contains("language = \"de_DE\""));
        assertTrue(emitted.contains("range = 72.0"));
        assertTrue(emitted.contains("aperture-padding-blocks = 0.75"));
        assertFalse(emitted.contains("blackout-shell-thickness-blocks"));
        assertTrue(emitted.contains("[network.transport]"));
        String content = String.join("\n", emitted);
        assertEveryKeyEmitted(content, WormholesConfigFile.class);
    }

    @Test
    void dimensionalDoorToggleRoundTripsAndRefreshesLiveSettings() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "schema = 2\n[main]\ndimensional-doors-enabled = false\n", StandardCharsets.UTF_8);

        WormholesSettings disabled = WormholesSettings.loadAll(tempDir);
        Settings.refresh(disabled);
        assertFalse(disabled.getMain().dimensionalDoorsEnabled);
        assertFalse(Settings.DIMENSIONAL_DOORS_ENABLED);

        Files.writeString(config, "schema = 2\n[main]\ndimensional-doors-enabled = true\n", StandardCharsets.UTF_8);
        WormholesSettings enabled = WormholesSettings.loadAll(tempDir);
        Settings.refresh(enabled);
        assertTrue(enabled.getMain().dimensionalDoorsEnabled);
        assertTrue(Settings.DIMENSIONAL_DOORS_ENABLED);
    }

    @Test
    void pocketShellSettingsRoundTripAndClampWithoutTouchingTheBlockRegistry() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            schema = 2
            [main]
            pocket-room-size = 64
            pocket-shell-material = "minecraft:obsidian"
            pocket-return-door-material = "oak_door"
            """, StandardCharsets.UTF_8);

        WormholesSettings sized = WormholesSettings.loadAll(tempDir);
        Settings.refresh(sized);

        assertEquals(64, Settings.POCKET_SHELL.size());
        assertEquals("OBSIDIAN", Settings.POCKET_SHELL.shellMaterial());
        assertEquals("OAK_DOOR", Settings.POCKET_SHELL.returnDoorMaterial());

        Files.writeString(config, """
            schema = 2
            [main]
            pocket-room-size = 4096
            """, StandardCharsets.UTF_8);
        Settings.refresh(WormholesSettings.loadAll(tempDir));

        assertEquals(PocketShell.MAX_SIZE, Settings.POCKET_SHELL.size());
        assertEquals(PocketShell.DEFAULT_SHELL_MATERIAL, Settings.POCKET_SHELL.shellMaterial());

        Files.writeString(config, """
            schema = 2
            [main]
            pocket-shell-material = ""
            """, StandardCharsets.UTF_8);
        Settings.refresh(WormholesSettings.loadAll(tempDir));

        assertEquals(PocketShell.defaults(), Settings.POCKET_SHELL);
    }

    @Test
    void doorRecipesToggleAndReshapeFromConfigWithoutTouchingTheBlockRegistry() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            schema = 2
            [recipes.pair-kit]
            shape = "RR|DD"
            ingredients = "R=#wormhole-rune, D=#doors"
            [recipes.public-door]
            enabled = false
            [recipes.trapdoor-skin]
            enabled = false
            """, StandardCharsets.UTF_8);

        Settings.refresh(WormholesSettings.loadAll(tempDir));
        DoorRecipeSettings recipes = Settings.DOOR_RECIPES;

        assertEquals("RR|DD", recipes.spec(DoorCraftProduct.PAIR_KIT).orElseThrow().shape().toString());
        assertFalse(recipes.isCraftable(DoorCraftProduct.PUBLIC_DOOR));
        assertTrue(recipes.isCraftable(DoorCraftProduct.PERSONAL_DOOR));
        assertTrue(recipes.doorSkinEnabled());
        assertFalse(recipes.trapdoorSkinEnabled());

        Settings.refresh(WormholesSettings.loadAll(tempDir.resolve("defaults")));
    }

    @Test
    void theShippedRecipeSectionSurvivesACanonicalWriteIncludingItsTrailingSpaces() throws IOException {
        WormholesSettings.loadAll(tempDir);
        String emitted = Files.readString(
            tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME), StandardCharsets.UTF_8);

        assertTrue(emitted.contains("[recipes.pair-kit]"), emitted);
        assertTrue(emitted.contains("[recipes.trapdoor-skin]"), emitted);
        // A shape's trailing space is a real slot; losing it through the writer
        // would silently narrow the grid.
        assertTrue(emitted.contains("shape = \"" + DoorCraftProduct.PAIR_KIT.defaultShape() + "\""), emitted);

        Settings.refresh(WormholesSettings.loadAll(tempDir));
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            assertEquals(product.defaultSpec(), Settings.DOOR_RECIPES.spec(product).orElseThrow(), product.name());
        }

        Settings.refresh(WormholesSettings.loadAll(tempDir.resolve("defaults")));
    }

    @Test
    void anUnusableRecipeFallsBackToTheShippedOneInsteadOfDisappearing() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            schema = 2
            [recipes.personal-door]
            shape = "TOOWIDE"
            ingredients = "T=STONE"
            [recipes.public-door]
            shape = "AB"
            ingredients = "A=STONE"
            """, StandardCharsets.UTF_8);

        Settings.refresh(WormholesSettings.loadAll(tempDir));
        DoorRecipeSettings recipes = Settings.DOOR_RECIPES;

        assertEquals(DoorCraftProduct.PERSONAL_DOOR.defaultSpec(),
            recipes.spec(DoorCraftProduct.PERSONAL_DOOR).orElseThrow(),
            "an over-wide grid falls back rather than removing the recipe");
        assertEquals(DoorCraftProduct.PUBLIC_DOOR.defaultSpec(),
            recipes.spec(DoorCraftProduct.PUBLIC_DOOR).orElseThrow(),
            "a slot with no ingredient falls back rather than removing the recipe");

        Settings.refresh(WormholesSettings.loadAll(tempDir.resolve("defaults")));
    }

    @Test
    void projectionInitialResendDefaultsToOneAndKeepsExplicitOverrides() {
        ProjectionConfig projection = new ProjectionConfig();
        refreshProjection(projection);
        assertEquals(1, Settings.PROJECTION_INITIAL_RESEND_PASSES);

        projection.initialResendPasses = 3;
        refreshProjection(projection);
        assertEquals(3, Settings.PROJECTION_INITIAL_RESEND_PASSES);

        refreshProjection(new ProjectionConfig());
    }

    @Test
    void schemaLessConsolidatedFileIsRejectedWithoutBeingRewritten() throws IOException {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Path file = configDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.writeString(file, """
            [main]
            enable-particles = false
            portal-construct-speed = 0.8
            replace-nether-and-end-portals = false

            [network]
            enabled = true
            listen-port = 8950
            trust-on-first-use = false

            [network.transport]
            compression-level = 6

            [projection]
            range = 76.0

            [render]
            entity-spoofing = false
            """, StandardCharsets.UTF_8);

        String original = Files.readString(file, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(tempDir));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void separateLegacyFilesAreIgnoredAndLeftUntouched() throws IOException {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);

        NetworkConfig network = new NetworkConfig();
        network.enabled = true;
        network.listenPort = 8950;
        network.transport.compressionLevel = 8;
        TomlCodec.writeCanonical(configDir.resolve("network.toml").toFile(), network);

        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        assertFalse(settings.getNetwork().enabled);
        assertEquals(8901, settings.getNetwork().listenPort);
        assertTrue(Files.isRegularFile(configDir.resolve("network.toml")));
        assertTrue(Files.isRegularFile(configDir.resolve(WormholesSettings.CONFIG_FILE_NAME)));
    }

    @Test
    void allQualityProfilesAreAcceptedAndAutoKeepsCurrentFidelity() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        for (VisualQualityProfile profile : VisualQualityProfile.values()) {
            Files.createDirectories(config.getParent());
            Files.writeString(config, "schema = 2\nquality = \"" + profile.configValue() + "\"\n", StandardCharsets.UTF_8);
            WormholesSettings settings = WormholesSettings.loadAll(tempDir);
            assertEquals(profile, settings.getVisualQualityProfile());
        }

        Files.writeString(config, "schema = 2\nquality = \"performance\"\n", StandardCharsets.UTF_8);
        WormholesSettings performance = WormholesSettings.loadAll(tempDir);
        Settings.refresh(performance);
        assertFalse(Settings.LIGHTING_FIDELITY);
        assertFalse(Settings.ENTITY_SPOOFING);
        assertEquals(32.0D, Settings.PROJECTION_RANGE);

        Files.writeString(config, "schema = 2\nquality = \"balanced\"\n", StandardCharsets.UTF_8);
        WormholesSettings balanced = WormholesSettings.loadAll(tempDir);
        Settings.refresh(balanced);
        assertEquals(2, Settings.ENTITY_UPDATE_INTERVAL_TICKS);
        assertEquals(16, Settings.MAX_SPOOFED_ENTITIES);

        Files.writeString(config, "schema = 2\nquality = \"cinematic\"\n", StandardCharsets.UTF_8);
        WormholesSettings cinematic = WormholesSettings.loadAll(tempDir);
        Settings.refresh(cinematic);
        assertEquals(64.0D, Settings.PROJECTION_RANGE);
        assertEquals(96, Settings.PROJECTION_DEPTH_BLOCKS);
        assertEquals(48, Settings.MAX_SPOOFED_ENTITIES);
        assertEquals(2, Settings.LIGHTING_REFRESH_INTERVAL_TICKS);

        Files.writeString(config, "schema = 2\nquality = \"auto\"\n", StandardCharsets.UTF_8);
        WormholesSettings automatic = WormholesSettings.loadAll(tempDir);
        Settings.refresh(automatic);
        assertFalse(Settings.LIGHTING_FIDELITY);
        assertTrue(Settings.ADAPTIVE_LIGHTING);
        assertTrue(Settings.ENTITY_SPOOFING);
        assertEquals(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
    }

    @Test
    void malformedExistingConfigIsRejectedWithoutBeingRewritten() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        String malformed = "schema = 2\nquality = \"unterminated\n";
        Files.writeString(config, malformed, StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> WormholesSettings.loadAll(tempDir));
        assertEquals(malformed, Files.readString(config, StandardCharsets.UTF_8));
    }

    @Test
    void unknownQualityAndUnsupportedSchemasAreRejected() throws IOException {
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "schema = 2\nquality = \"ultra\"\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(tempDir));

        Files.writeString(config, "schema = 99\nquality = \"auto\"\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(tempDir));

        Files.writeString(config, "schema = 1\nquality = \"auto\"\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(tempDir));
    }

    private static List<String> emittedSettings(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }

    private static void refreshProjection(ProjectionConfig projection) {
        Settings.refresh(new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new NetworkConfig()));
    }

    private static void assertEveryKeyEmitted(String content, Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                continue;
            }
            if (List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (field.getType().getName().startsWith("art.arcane.wormholes.config.toml")) {
                assertEveryKeyEmitted(content, field.getType());
                continue;
            }
            String key = kebabKey(field.getName());
            assertTrue(content.contains(key + " = "), "missing config key: " + key);
        }
    }

    private static String kebabKey(String fieldName) {
        StringBuilder out = new StringBuilder(fieldName.length() + 4);
        for (int index = 0; index < fieldName.length(); index++) {
            char character = fieldName.charAt(index);
            if (Character.isUpperCase(character)) {
                if (index > 0) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(character));
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }
}
