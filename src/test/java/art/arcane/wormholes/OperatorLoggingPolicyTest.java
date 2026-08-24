package art.arcane.wormholes;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorLoggingPolicyTest {
    private static final Path PRODUCTION_SOURCE = Path.of("src/main/java");

    @Test
    void productionLoggingNeverBypassesThePluginLogger() throws Exception {
        List<String> violations = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(PRODUCTION_SOURCE)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (source.contains("System.out") || source.contains("System.err") || source.contains(".printStackTrace()")) {
                    violations.add(path.toString());
                }
            }
        }

        assertTrue(violations.isEmpty(), "raw console output in " + violations);
    }

    @Test
    void routineNetworkAndPortalMessagesAreVerbose() throws Exception {
        String traversal = Files.readString(PRODUCTION_SOURCE.resolve(
            "art/arcane/wormholes/network/TraversalService.java"));
        String arrival = Files.readString(PRODUCTION_SOURCE.resolve(
            "art/arcane/wormholes/network/TraversalArrivalPlacer.java"));
        String nether = Files.readString(PRODUCTION_SOURCE.resolve(
            "art/arcane/wormholes/portal/vanilla/VanillaPortalNetherPairing.java"));

        assertFalse(traversal.contains("Wormholes.i("));
        assertFalse(arrival.contains("Wormholes.i("));
        assertTrue(nether.contains("Wormholes.v(() -> \"[vanilla-portal] source built"));
        assertTrue(nether.contains("Wormholes.v(() -> \"[vanilla-portal] counterpart frame built"));
    }
}
