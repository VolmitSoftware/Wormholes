package art.arcane.wormholes.service;

import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesIntegrationServiceTest {
    private static final String SERVICE = "art/arcane/wormholes/service/WormholesIntegrationService";
    private static final String NETWORK = "art/arcane/wormholes/network/NetworkManager";

    @Test
    void peersConnectedUsesReadyWireLinksNotSidebandSnapshots() throws IOException {
        ClassModel model = parse(SERVICE);
        Set<String> invocations = invoked(body(model, "samplePeersConnected"));

        assertTrue(invocations.contains(NETWORK + ".connectedPeers"),
            "wormholes.peers-connected must use NetworkManager.connectedPeers() (READY wire links)");
        assertFalse(invocations.contains(NETWORK + ".peerSnapshots"),
            "wormholes.peers-connected must not count sideband last-seen peers from peerSnapshots()");
    }

    @Test
    void peersConnectedIsUnavailableWhenTheNetworkManagerIsMissing() {
        WormholesIntegrationService service = new WormholesIntegrationService();
        Map<String, IntegrationMetricSample> samples = service.sampleMetrics(
            Set.of(IntegrationMetricSchema.WORMHOLES_PEERS_CONNECTED));
        IntegrationMetricSample sample = samples.get(IntegrationMetricSchema.WORMHOLES_PEERS_CONNECTED);

        assertNotNull(sample);
        assertFalse(sample.available());
        assertEquals("network-manager-not-ready", sample.message());
    }

    private static Set<String> invoked(List<Instruction> body) {
        Set<String> invocations = new LinkedHashSet<String>();
        for (Instruction instruction : body) {
            if (instruction instanceof InvokeInstruction invoke) {
                invocations.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue());
            }
        }
        return invocations;
    }

    private static List<Instruction> body(ClassModel model, String methodName) {
        for (MethodModel method : model.methods()) {
            if (!method.methodName().equalsString(methodName)) {
                continue;
            }
            Optional<CodeModel> code = method.code();
            if (code.isPresent()) {
                return instructions(code.get());
            }
        }
        throw new AssertionError("No method named " + methodName + " in " + model.thisClass().asInternalName());
    }

    private static List<Instruction> instructions(CodeModel code) {
        List<Instruction> body = new ArrayList<Instruction>();
        for (CodeElement element : code.elementList()) {
            if (element instanceof Instruction instruction) {
                body.add(instruction);
            }
        }
        return body;
    }

    private static ClassModel parse(String internalName) throws IOException {
        try (InputStream input = WormholesIntegrationServiceTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(input, "Compiled class is missing: " + internalName);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
