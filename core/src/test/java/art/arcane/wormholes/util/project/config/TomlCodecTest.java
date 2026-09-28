package art.arcane.wormholes.util.project.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;

public final class TomlCodecTest {
    @TempDir
    private Path tempDir;

    @Test
    public void loadOrCreateDoesNotRewriteUnchangedCanonicalFile() throws Exception {
        File file = tempDir.resolve("main.toml").toFile();
        TomlCodec.writeCanonical(file, new MainConfig());
        FileTime marker = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(file.toPath(), marker);

        TomlCodec.loadOrCreate(file, MainConfig.class);

        assertEquals(marker.toMillis(), Files.getLastModifiedTime(file.toPath()).toMillis());
    }

    @Test
    public void loadOrCreateStillCanonicalizesChangedFileContent() throws Exception {
        File file = tempDir.resolve("main.toml").toFile();
        TomlCodec.writeCanonical(file, new MainConfig());
        String canonical = Files.readString(file.toPath());
        Files.writeString(file.toPath(), canonical + "\n");

        TomlCodec.loadOrCreate(file, MainConfig.class);

        assertEquals(canonical, Files.readString(file.toPath()));
    }

    @Test
    public void stringArraysRoundTripEscapesAndEmptyValues() {
        StringArrayConfig original = new StringArrayConfig();
        original.values = List.of("beta", "", "quoted \"name\"", "slash\\name", "line\nreturn\rtab\tback\bform\f",
            "control" + (char) 1 + (char) 127, "Grüße", "mixed\\name'\"\n" + (char) 1,
            "trailing\\", "literal\\u006e");

        String content = TomlCodec.canonicalContent(original);
        TomlCodec.LoadResult<StringArrayConfig> loaded = TomlCodec.readContent(content, StringArrayConfig.class);

        assertTrue(content.contains("values = [\"beta\", \"\""));
        assertTrue(loaded.isSuccess(), () -> String.valueOf(loaded.error()));
        assertEquals(original.values, loaded.value().values);
        assertEquals(content, TomlCodec.canonicalContent(loaded.value()));
    }

    @Test
    public void emptyStringArraysAreWrittenAsArrays() {
        String content = TomlCodec.canonicalContent(new StringArrayConfig());
        TomlCodec.LoadResult<StringArrayConfig> loaded = TomlCodec.readContent(content, StringArrayConfig.class);

        assertEquals("values = []\n", content);
        assertTrue(loaded.isSuccess());
        assertEquals(List.of(), loaded.value().values);
    }

    @Test
    public void stringArraysRejectScalarAndNonStringContents() {
        for (String content : List.of("values = \"beta\"", "values = 12", "values = [1, 2]",
            "values = [true, false]", "values = [[\"beta\"]]", "[[values]]\nname = \"beta\"")) {
            TomlCodec.LoadResult<StringArrayConfig> loaded = TomlCodec.readContent(content, StringArrayConfig.class);

            assertFalse(loaded.isSuccess(), content);
            assertTrue(loaded.error() instanceof IllegalArgumentException, () -> String.valueOf(loaded.error()));
        }
    }

    @Test
    public void stringArraysRejectNullEntriesBeforeWriting() {
        StringArrayConfig original = new StringArrayConfig();
        original.values.add(null);

        assertThrows(IllegalArgumentException.class, () -> TomlCodec.canonicalContent(original));
    }

    @Test
    public void integerOverflowCannotWrapIntoAValidPort() {
        for (String value : List.of("4294992861", "2147483648", "-2147483649")) {
            TomlCodec.LoadResult<IntegerConfig> loaded = TomlCodec.readContent("port = " + value, IntegerConfig.class);

            assertFalse(loaded.isSuccess(), value);
            assertTrue(loaded.error() instanceof ArithmeticException, () -> String.valueOf(loaded.error()));
        }
        TomlCodec.LoadResult<IntegerConfig> loaded = TomlCodec.readContent("port = 25565", IntegerConfig.class);
        assertTrue(loaded.isSuccess());
        assertEquals(25565, loaded.value().port);
    }

    @Test
    public void inlineTablesAndMissingFieldsKeepTheirDeclaredTypesAndDefaults() {
        String content = """
            client-routes = [{ server = "beta", host = "play.example", port = 25567 }]
            proxy-servers = ["beta"]
            transport = { compression-enabled = false }
            """;

        TomlCodec.LoadResult<NetworkConfig> loaded = TomlCodec.readContent(content, NetworkConfig.class);

        assertTrue(loaded.isSuccess(), () -> String.valueOf(loaded.error()));
        assertEquals("beta", loaded.value().clientRoutes.getFirst().server);
        assertEquals(25567, loaded.value().clientRoutes.getFirst().port);
        assertEquals(List.of("beta"), loaded.value().proxyServers);
        assertFalse(loaded.value().transport.compressionEnabled);
        assertEquals(3, loaded.value().transport.compressionLevel);
        assertEquals("auto", loaded.value().transferMode);
    }

    @Test
    public void tableFieldsRejectScalarsAndNestedArrays() {
        for (String content : List.of("transport = 3", "client-routes = [\"beta\"]",
            "client-routes = [[{ server = \"beta\" }]]")) {
            TomlCodec.LoadResult<NetworkConfig> loaded = TomlCodec.readContent(content, NetworkConfig.class);

            assertFalse(loaded.isSuccess(), content);
            assertTrue(loaded.error() instanceof IllegalArgumentException, () -> String.valueOf(loaded.error()));
        }
    }

    public static final class StringArrayConfig {
        public List<String> values = new ArrayList<>();
    }

    public static final class IntegerConfig {
        public int port;
    }
}
